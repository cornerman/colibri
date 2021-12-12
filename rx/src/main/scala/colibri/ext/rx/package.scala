package colibri.ext

import _root_.rx._

import colibri.helpers._
import colibri._

package object rx {

  // Sink
  implicit object rxVarSink extends Sink[Var] {
    def onNext[A](sink: Var[A])(value: A): Unit = sink() = value

    def onError[A](sink: Var[A])(error: Throwable): Unit = UnhandledErrorReporter.errorSubject.onNext(error)
  }

  // Source
  implicit object rxRxSource extends Source[Rx] {
    def subscribe[A](stream: Rx[A])(sink: Observer[A]): Cancelable = {
      implicit val ctx = Ctx.Owner.Unsafe
      sink.onNext(stream.now)
      val obs          = stream.triggerLater(sink.onNext(_))
      Cancelable(() => obs.kill())
    }
  }

  // Cancelable
  implicit object obsCanCancel extends CanCancel[Obs] {
    def cancel(obs: Obs) = obs.kill()
  }
}
