package colibri.ext

import _root_.monix.eval.Coeval
import _root_.monix.execution.{Ack, Scheduler, Cancelable}
import _root_.monix.execution.cancelables.CompositeCancelable
import _root_.monix.reactive.{OverflowStrategy, Observable, Observer}
import _root_.monix.reactive.subjects.Var
import cats.Monoid

import colibri.effect._
import colibri.helpers._
import colibri._

package object monix {

  // Sink

  implicit object monixVariableSink extends Sink[Var] {
    def onNext[A](sink: Var[A])(value: A): Unit = { sink := value; () }

    def onError[A](sink: Var[A])(error: Throwable): Unit = UnhandledErrorReporter.errorSubject.onNext(error)
  }

  //TODO: unsafe because of backpressure and ignored ACK
  implicit object monixObserverSink extends Sink[Observer] {
    def onNext[A](sink: Observer[A])(value: A): Unit = {
      sink.onNext(value)
      ()
    }

    def onError[A](sink: Observer[A])(error: Throwable): Unit = {
      sink.onError(error)
      ()
    }
  }

  implicit object monixObserverLiftSink extends LiftSink[Observer.Sync] {
    def lift[G[_] : Sink, A](sink: G[A]): Observer.Sync[A] = new Observer.Sync[A] {
      def onNext(value: A): Ack = { Sink[G].onNext(sink)(value); Ack.Continue }
      def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
      def onComplete(): Unit = ()
    }
  }

  // Source

  implicit def monixObservableSource(implicit scheduler: Scheduler): Source[Observable] = new Source[Observable] {
    def subscribe[G[_] : Sink, A](source: Observable[A])(sink: G[_ >: A]): colibri.Cancelable = {
      val sub = source.subscribe(
        { v => Sink[G].onNext(sink)(v); Ack.Continue },
        Sink[G].onError(sink)
      )
      colibri.Cancelable(sub.cancel)
    }
  }

  implicit object monixObservableLiftSource extends LiftSource[Observable] {
    def lift[G[_] : Source, A](source: G[A]): Observable[A] = Observable.create[A](OverflowStrategy.Unbounded) { observer =>
      val sub = Source[G].subscribe(source)(observer)
      Cancelable(() => sub.cancel())
    }
  }

  // Cancelable
  implicit object monixCanCancel extends CanCancel[Cancelable] {
    def cancel(subscription: Cancelable): Unit = subscription.cancel()
  }

  implicit object coeval extends RunSyncEffect[Coeval] {
    @inline def unsafeRun[T](effect: Coeval[T]): T = effect.apply()
  }

  //TODO: add to monix?
  implicit object CancelableMonoid extends Monoid[Cancelable] {
    def empty: Cancelable = Cancelable.empty
    def combine(x: Cancelable, y: Cancelable): Cancelable = CompositeCancelable(x, y)
  }

  type MonixProSubject[-I, +O] = Observable[O] with Observer[I]
  type MonixSubject[T] = MonixProSubject[T,T]

  implicit object monixCreateSubject extends CreateSubject[MonixSubject] {
    def replay[A]: MonixSubject[A] = MonixSubject.replay[A]
    def behavior[A](seed: A): MonixSubject[A] = MonixSubject.behavior[A](seed)
    def publish[A]: MonixSubject[A] = MonixSubject.publish[A]
  }

  implicit object monixCreateProSubject extends CreateProSubject[MonixProSubject] {
    @inline def from[SI[_] : Sink, SO[_] : Source, I,O](sink: SI[I], source: SO[O]): MonixProSubject[I, O] = MonixProSubject.from(LiftSink[Observer].lift(sink), LiftSource[Observable].lift(source))
  }
}
