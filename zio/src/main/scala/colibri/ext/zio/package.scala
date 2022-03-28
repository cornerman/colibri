package colibri.ext

import colibri._
import colibri.effect._

import _root_.zio.stream.{Stream, UStream, ZSink, ZStream}
import _root_.zio.{Runtime, UIO, ZEnv, RIO}

trait ZioLowPrio {
  type RSink[-Env, A]    = ZSink[Env, Throwable, A, A, Unit]
  type RStream[-Env, +A] = ZStream[Env, Throwable, A]

  implicit val zioTaskRunEffect: RunEffect[RIO[ZEnv, *]] = new RunEffectZIOWithRuntime(Runtime.default)
  implicit val zioSinkSink: Sink[RSink[ZEnv, *]]         = new SinkZIOWithRuntime(Runtime.default)
  implicit val zioStreamSource: Source[RStream[ZEnv, *]] = new SourceZIOWithRuntime(Runtime.default)
}

package object zio extends ZioLowPrio {
  implicit def zioTaskRunEffectRuntime[Env](implicit runtime: Runtime[Env]): RunEffect[RIO[Env, *]] =
    new RunEffectZIOWithRuntime[Env](runtime)

  // Sink

  implicit def zioSinkSinkRuntime[Env](implicit runtime: Runtime[Env]): Sink[RSink[Env, *]] = new SinkZIOWithRuntime(runtime)

  implicit object zioSinkLiftSink extends LiftSink[RSink[Any, *]] {
    def lift[G[_]: Sink, A](sink: G[A]): RSink[Any, A] = ZSink
      .foreach[Any, Throwable, A](elem => UIO(Sink[G].unsafeOnNext(sink)(elem)))
      .foldM(error => ZSink.fromEffect(UIO(Sink[G].unsafeOnError(sink)(error))), _ => ZSink.drain)
  }

  // Source

  implicit def zioStreamSourceRuntime[Env](implicit runtime: Runtime[Env]): Source[RStream[Env, *]] = new SourceZIOWithRuntime(runtime)

  implicit object zioStreamLiftSource extends LiftSource[RStream[Any, *]] {
    override def lift[G[_]: Source, A](source: G[A]): RStream[Any, A] = Stream.effectAsyncInterrupt { emit =>
      val cancelable = Source[G].unsafeSubscribe(source)(new Observer[A] {
        override def unsafeOnNext(value: A): Unit          = { emit.single(value); () }
        override def unsafeOnError(error: Throwable): Unit = { emit.fail(error); () }
      })
      Left(UIO(cancelable.unsafeCancel()))
    }
  }
}

private final class SinkZIOWithRuntime[Env](runtime: Runtime[Env]) extends Sink[zio.RSink[Env, *]] {
  override def unsafeOnNext[A](sink: zio.RSink[Env, A])(value: A): Unit =
    runtime.unsafeRun(UStream(value).run(sink))

  override def unsafeOnError[A](sink: zio.RSink[Env, A])(error: Throwable): Unit =
    runtime.unsafeRun(ZStream.fail(error).run(sink))
}

private final class SourceZIOWithRuntime[Env](runtime: Runtime[Env]) extends Source[zio.RStream[Env, *]] {
  override def unsafeSubscribe[A](source: zio.RStream[Env, A])(sink: Observer[A]): Cancelable = {
    val canceler = runtime.unsafeRunToFuture(
      source
        .onError(cause => UIO(sink.unsafeOnError(cause.squash)))
        .foreach(value => UIO(sink.unsafeOnNext(value))),
    )

    Cancelable { () =>
      canceler.cancel()
      ()
    }
  }
}

private final class RunEffectZIOWithRuntime[Env](runtime: Runtime[Env]) extends RunEffect[RIO[Env, *]] {
  override def unsafeRunAsyncCancelable[T](effect: RIO[Env, T])(cb: Either[Throwable, T] => Unit): Cancelable =
    unsafeRunSyncOrAsyncCancelable(RIO.yieldNow *> effect)(cb)

  override def unsafeRunSyncOrAsyncCancelable[T](effect: RIO[Env, T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val cancelableFuture = runtime.unsafeRunToFuture(effect)
    RunEffectExecution.handleFutureCancelable(cancelableFuture, cancelableFuture.cancel)(cb)
  }
}
