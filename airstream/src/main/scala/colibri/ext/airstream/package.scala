package colibri.ext

import com.raquo.airstream.core.{Observable, Observer}
import com.raquo.airstream.ownership.Subscription
import com.raquo.airstream.ownership.internalcolibri.NoopOwner

package object airstream {

  // Sink
  implicit object airstreamObserverSink extends colibri.Sink[Observer] {
    def unsafeOnNext[A](sink: Observer[A])(value: A): Unit          = sink.onNext(value)
    def unsafeOnError[A](sink: Observer[A])(error: Throwable): Unit = sink.onError(error)
  }

  // Source
  implicit object airstreamObservableSource extends colibri.Source[Observable] {
    def unsafeSubscribe[A](stream: Observable[A])(sink: colibri.Observer[A]): colibri.Cancelable = {
      val sub = stream.addObserver(Observer.withRecover(sink.unsafeOnNext, { case t => sink.unsafeOnError(t) }))(NoopOwner)
      colibri.Cancelable(sub.kill)
    }
  }

  // Cancelable
  implicit object airstreamSubscriptionCanCancel extends colibri.CanCancel[Subscription] {
    def unsafeCancel(subscription: Subscription) = subscription.kill()
  }
}
