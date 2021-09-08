package colibri.ext

import _root_.zio.stream.{Stream, UStream, ZSink, ZStream}
import _root_.zio.{Ref, Runtime, UIO, ZEnv, URIO}
import colibri._

package object zio {
  type TSink[A] = ZSink[Any, Throwable, A, A, Unit]

  // Sink

  implicit def zioSinkSink(implicit runtime: Runtime[ZEnv]): Sink[TSink] = new Sink[TSink] {
    def onNext[A](sink: TSink[A])(value: A): Unit =
      runtime.unsafeRun(UStream(value).run(sink))

    def onError[A](sink: TSink[A])(error: Throwable): Unit =
      runtime.unsafeRun(ZStream.fail(error).run(sink))
  }

  implicit object zioSinkLiftSink extends LiftSink[TSink] {
    def lift[G[_]: Sink, A](sink: G[A]): TSink[A] = ZSink
      .foreach[Any, Throwable, A](elem => UIO(Sink[G].onNext(sink)(elem)))
      .foldM(error => ZSink.fromEffect(UIO(Sink[G].onError(sink)(error))), _ => ZSink.drain)
  }

  // Source

  type TStream[A] = ZStream[Any, Throwable, A]

  implicit def zioStreamSource(implicit runtime: Runtime[ZEnv]): Source[TStream] = new Source[TStream] {
    def subscribe[G[_] : Sink, A](source: TStream[A])(sink: G[_ >: A]): Cancelable = {
      val canceler = runtime.unsafeRunToFuture(
        source
          .onError(cause => UIO(Sink[G].onError(sink)(cause.squash)))
          .foreach(value => UIO(Sink[G].onNext(sink)(value)))
      )
      Cancelable(() => canceler.cancel())
    }
  }

  implicit object zioStreamLiftSource extends LiftSource[TStream] {
    override def lift[G[_] : Source, A](source: G[A]): TStream[A] = Stream.effectAsyncInterrupt { emit =>
      val cancelable = Source[G].subscribe(source)(new Observer[A] {
        override def onNext(value: A): Unit = { emit.single(value); () }
        override def onError(error: Throwable): Unit = { emit.fail(error); () }
      })
      Left(URIO(cancelable.cancel()))
    }
  }
}
