package colibri.ext

import _root_.rx._

import colibri.helpers._
import colibri._

package object rx {

  // Sink
  implicit object rxVarSink extends Sink[Var] {
    def unsafeOnNext[A](sink: Var[A])(value: A): Unit = sink() = value

    def unsafeOnError[A](sink: Var[A])(error: Throwable): Unit = UnhandledErrorReporter.errorSubject.unsafeOnNext(error)
  }

  // Source
  implicit object rxRxSource extends Source[Rx] {
    def unsafeSubscribe[A](stream: Rx[A])(sink: Observer[A]): Cancelable = {
      implicit val ctx = Ctx.Owner.Unsafe
      sink.unsafeOnNext(stream.now)
      val obs          = stream.triggerLater(sink.unsafeOnNext(_))
      Cancelable(() => obs.kill())
    }
  }

  // Cancelable
  implicit object obsCanCancel extends CanCancel[Obs] {
    def unsafeCancel(obs: Obs) = obs.kill()
  }
}
