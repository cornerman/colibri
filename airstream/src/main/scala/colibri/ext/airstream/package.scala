package colibri.ext

import com.raquo.airstream.core.{Observable, Observer, EventStream}
import com.raquo.airstream.ownership.Subscription
import com.raquo.airstream.ownership.internalcolibri.NoopOwner

package object airstream {

  // Sink
  implicit object airstreamObserverSink extends colibri.Sink[Observer] {
    def unsafeOnNext[A](sink: Observer[A])(value: A): Unit          = sink.onNext(value)
    def unsafeOnError[A](sink: Observer[A])(error: Throwable): Unit = sink.onError(error)
  }

  implicit object liftSink extends colibri.LiftSink[Observer] {
    @inline def lift[G[_]: colibri.Sink, A](sink: G[A]): Observer[A] = Observer.withRecover(
      colibri.Sink[G].unsafeOnNext(sink),
      { case t => colibri.Sink[G].unsafeOnError(sink)(t) },
    )
  }

  // Source
  implicit object airstreamObservableSource extends colibri.Source[Observable] {
    def unsafeSubscribe[A](stream: Observable[A])(sink: colibri.Observer[A]): colibri.Cancelable = {
      val sub = stream.addObserver(Observer.withRecover(sink.unsafeOnNext, { case t => sink.unsafeOnError(t) }))(NoopOwner)
      colibri.Cancelable(sub.kill)
    }
  }

  implicit object liftSource extends colibri.LiftSource[Observable] {
    def lift[H[_]: colibri.Source, A](source: H[A]): Observable[A] = {
      var cancelable = colibri.Cancelable.empty
      EventStream.fromCustomSource[A](
        start = (fireValue, fireError, _, _) =>
          cancelable = colibri.Source[H].unsafeSubscribe(source)(colibri.Observer.create(fireValue, fireError)),
        stop = _ => cancelable.unsafeCancel(),
      )
    }
  }

  // Cancelable
  implicit object airstreamSubscriptionCanCancel extends colibri.CanCancel[Subscription] {
    def unsafeCancel(subscription: Subscription) = subscription.kill()
  }
}
