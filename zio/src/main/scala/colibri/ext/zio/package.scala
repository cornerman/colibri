package colibri.ext

import colibri._
import colibri.effect._

import _root_.zio.stream.{Stream, UStream, ZSink, ZStream}
import _root_.zio.{Task, Runtime, UIO, ZEnv, URIO}

trait ZioLowPrio {
  type TSink[A] = ZSink[Any, Throwable, A, A, Unit]
  type TStream[A] = ZStream[Any, Throwable, A]

  implicit val zioTaskRunEffect: RunEffect[Task] = new RunEffectZIOWithRuntime(Runtime.default)
  implicit val zioSinkSink: Sink[TSink] = new SinkZIOWithRuntime(Runtime.default)
  implicit val zioStreamSource: Source[TStream] = new SourceZIOWithRuntime(Runtime.default)
}

package object zio extends ZioLowPrio {
  implicit def zioTaskRunEffectRuntime(implicit runtime: Runtime[ZEnv]): RunEffect[Task] = new RunEffectZIOWithRuntime(runtime)

  // Sink

  implicit def zioSinkSinkRuntime(implicit runtime: Runtime[ZEnv]): Sink[TSink] = new SinkZIOWithRuntime(runtime)

  implicit object zioSinkLiftSink extends LiftSink[TSink] {
    def lift[G[_]: Sink, A](sink: G[A]): TSink[A] = ZSink
      .foreach[Any, Throwable, A](elem => UIO(Sink[G].unsafeOnNext(sink)(elem)))
      .foldM(error => ZSink.fromEffect(UIO(Sink[G].unsafeOnError(sink)(error))), _ => ZSink.drain)
  }

  // Source

  implicit def zioStreamSourceRuntime(implicit runtime: Runtime[ZEnv]): Source[TStream] = new SourceZIOWithRuntime(runtime)

  implicit object zioStreamLiftSource extends LiftSource[TStream] {
    override def lift[G[_] : Source, A](source: G[A]): TStream[A] = Stream.effectAsyncInterrupt { emit =>
      val cancelable = Source[G].unsafeSubscribe(source)(new Observer[A] {
        override def unsafeOnNext(value: A): Unit = { emit.single(value); () }
        override def unsafeOnError(error: Throwable): Unit = { emit.fail(error); () }
      })
      Left(URIO(cancelable.unsafeCancel()))
    }
  }
}

private class SinkZIOWithRuntime(runtime: Runtime[ZEnv]) extends Sink[zio.TSink] {
    def unsafeOnNext[A](sink: zio.TSink[A])(value: A): Unit =
      runtime.unsafeRun(UStream(value).run(sink))

    def unsafeOnError[A](sink: zio.TSink[A])(error: Throwable): Unit =
      runtime.unsafeRun(ZStream.fail(error).run(sink))
}

private class SourceZIOWithRuntime(runtime: Runtime[ZEnv]) extends Source[zio.TStream] {
    def unsafeSubscribe[A](source: zio.TStream[A])(sink: Observer[A]): Cancelable = {
      val canceler = runtime.unsafeRunToFuture(
        source
          .onError(cause => UIO(sink.unsafeOnError(cause.squash)))
          .foreach(value => UIO(sink.unsafeOnNext(value)))
      )

      Cancelable { () =>
        canceler.cancel()
        ()
      }
    }
}

private class RunEffectZIOWithRuntime(runtime: Runtime[ZEnv]) extends RunEffect[Task] {
  override def unsafeRunAsyncCancelable[T](effect: Task[T], cb: Either[Throwable, T] => Unit): Cancelable = unsafeRunSyncOrAsyncCancelable(Task.yieldNow *> effect, cb)

  override def unsafeRunSyncOrAsyncCancelable[T](effect: Task[T], cb: Either[Throwable, T] => Unit): Cancelable = {
    val cancelableFuture = runtime.unsafeRunToFuture(effect)
    RunEffectExecution.handleFutureCancelable(cancelableFuture, cancelableFuture.cancel, cb)
  }
}
