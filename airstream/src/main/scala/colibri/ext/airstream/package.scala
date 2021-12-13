package colibri.ext

import com.raquo.airstream.core.{Observable, Observer}
import com.raquo.airstream.ownership.Subscription
import com.raquo.airstream.ownership.internalcolibri.NoopOwner

package object airstream {

  // Sink
  implicit object airstreamObserverSink extends colibri.Sink[Observer] {
    def onNext[A](sink: Observer[A])(value: A): Unit          = sink.onNext(value)
    def onError[A](sink: Observer[A])(error: Throwable): Unit = sink.onError(error)
  }

  // Source
  implicit object airstreamObservableSource extends colibri.Source[Observable] {
    def subscribe[A](stream: Observable[A])(sink: colibri.Observer[A]): colibri.Cancelable = {
      val sub = stream.addObserver(Observer.withRecover(sink.onNext, { case t => sink.onError(t) }))(NoopOwner)
      colibri.Cancelable(sub.kill)
    }
  }

  // Cancelable
  implicit object airstreamSubscriptionCanCancel extends colibri.CanCancel[Subscription] {
    def cancel(subscription: Subscription) = subscription.kill()
  }
}
