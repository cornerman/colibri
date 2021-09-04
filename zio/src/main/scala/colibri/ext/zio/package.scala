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
    def subscribe[G[_] : Sink, A](source: TStream[A])(sink: G[_ >: A]): Cancelable =
      runtime.unsafeRun(for {
        running <- Ref.make[Boolean](false)
        valueFn = Sink[G].onNext(sink)_
        errorFn = Sink[G].onError(sink)_
        consumer = ZSink.foreachWhile[Any, Throwable, A](
          value => UIO(valueFn(value)) *> running.get
        ).foldM[Any, Throwable, A, A, Unit](error => ZSink.fromEffect(UIO(errorFn(error))), _ => ZSink.drain)
        _ <- source.run(consumer)
      } yield Cancelable(() => runtime.unsafeRun(running.set(false))))
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
