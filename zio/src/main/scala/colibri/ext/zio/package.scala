package colibri.ext

import colibri._
import colibri.effect._

import _root_.zio.stream.{ZSink, ZStream}
import _root_.zio.{Runtime, ZIO, RIO, Unsafe}

trait ZioLowPrio {
  type RSink[-Env, A]    = ZSink[Env, Throwable, A, A, Unit]
  type RStream[-Env, +A] = ZStream[Env, Throwable, A]

  implicit val zioTaskRunEffect: RunEffect[RIO[Any, *]] = new RunEffectZIOWithRuntime(Runtime.default)
  implicit val zioSinkSink: Sink[RSink[Any, *]]         = new SinkZIOWithRuntime(Runtime.default)
  implicit val zioStreamSource: Source[RStream[Any, *]] = new SourceZIOWithRuntime(Runtime.default)
}

package object zio extends ZioLowPrio {
  @inline implicit def zioTaskRunEffectRuntime[Env](implicit runtime: Runtime[Env]): RunEffect[RIO[Env, *]] =
    new RunEffectZIOWithRuntime[Env](runtime)

  // Sink

  @inline implicit def zioSinkSinkRuntime[Env](implicit runtime: Runtime[Env]): Sink[RSink[Env, *]] = new SinkZIOWithRuntime(runtime)

  implicit object zioSinkLiftSink extends LiftSink[RSink[Any, *]] {
    def lift[G[_]: Sink, A](sink: G[A]): RSink[Any, A] = ZSink
      .foreach[Any, Throwable, A](elem => ZIO.succeed(Sink[G].unsafeOnNext(sink)(elem)))
      .foldSink(error => ZSink.fromZIO(ZIO.succeed(Sink[G].unsafeOnError(sink)(error))), _ => ZSink.drain)
  }

  // Source

  @inline implicit def zioStreamSourceRuntime[Env](implicit runtime: Runtime[Env]): Source[RStream[Env, *]] = new SourceZIOWithRuntime(
    runtime,
  )

  implicit object zioStreamLiftSource extends LiftSource[RStream[Any, *]] {
    override def lift[G[_]: Source, A](source: G[A]): RStream[Any, A] = ZStream.fromZIO(ZIO.asyncInterrupt { emit =>
      val cancelable = Source[G].unsafeSubscribe(source)(new Observer[A] {
        override def unsafeOnNext(value: A): Unit          = emit(ZIO.succeed(value))
        override def unsafeOnError(error: Throwable): Unit = emit(ZIO.fail(error))
      })
      Left(ZIO.succeed(cancelable.unsafeCancel()))
    })
  }
}

private final class SinkZIOWithRuntime[Env](runtime: Runtime[Env]) extends Sink[zio.RSink[Env, *]] {
  override def unsafeOnNext[A](sink: zio.RSink[Env, A])(value: A): Unit =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(ZStream.succeed(value).run(sink)).getOrThrow())

  override def unsafeOnError[A](sink: zio.RSink[Env, A])(error: Throwable): Unit =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(ZStream.fail(error).run(sink)).getOrThrow())
}

private final class SourceZIOWithRuntime[Env](runtime: Runtime[Env]) extends Source[zio.RStream[Env, *]] {
  override def unsafeSubscribe[A](source: zio.RStream[Env, A])(sink: Observer[A]): Cancelable = {
    Unsafe.unsafe { implicit u =>
      val canceler = runtime.unsafe.runToFuture(
        source
          .onError(cause => ZIO.succeed(sink.unsafeOnError(cause.squash)))
          .foreach(value => ZIO.succeed(sink.unsafeOnNext(value))),
      )

      Cancelable.withIsEmpty(canceler.isCompleted) { () =>
        canceler.cancel(): Unit
      }
    }
  }
}

private final class RunEffectZIOWithRuntime[Env](runtime: Runtime[Env]) extends RunEffect[RIO[Env, *]] {
  override def unsafeRunAsyncCancelable[T](effect: RIO[Env, T])(cb: Either[Throwable, T] => Unit): Cancelable =
    unsafeRunSyncOrAsyncCancelable(ZIO.yieldNow *> effect)(cb)

  override def unsafeRunSyncOrAsyncCancelable[T](effect: RIO[Env, T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val cancelableFuture = Unsafe.unsafe(implicit u => runtime.unsafe.runToFuture(effect))
    RunEffectExecution.handleFutureCancelable(cancelableFuture, cancelableFuture.cancel)(cb)
  }
}
