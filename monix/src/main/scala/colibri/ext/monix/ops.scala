package colibri.ext.monix

import _root_.monix.execution.Ack
import _root_.monix.reactive.{Observable, Observer}
import _root_.monix.reactive.subjects.PublishSubject

import scala.concurrent.Future

object ops {
  implicit class RichObserver[I](val observer: Observer[I]) extends AnyVal {
    def redirect[I2](f: Observable[I2] => Observable[I]): ConnectableObserver[I2] = {
      val subject = PublishSubject[I2]
      val transformed = f(subject)
      new ConnectableObserver[I2](subject, implicit scheduler => transformed.subscribe(observer))
    }

    @deprecated("Use contramap instead.", "")
    @inline def redirectMap[I2](f: I2 => I): Observer[I2] = observer.contramap(f)

    def redirectMapMaybe[I2](f: I2 => Option[I]): Observer[I2] = new Observer[I2] {
      override def onNext(elem: I2): Future[Ack] = f(elem).fold[Future[Ack]](Ack.Continue)(observer.onNext(_))
      override def onError(ex: Throwable): Unit = observer.onError(ex)
      override def onComplete(): Unit = observer.onComplete()
    }

    def redirectCollect[I2](f: PartialFunction[I2, I]): Observer[I2] = redirectMapMaybe(f.lift)
    def redirectFilter(f: I => Boolean): Observer[I] = redirectMapMaybe(e => Some(e).filter(f))
  }

  implicit class RichProSubject[I,O](val self: MonixProSubject[I,O]) extends AnyVal {
    def mapObservable[O2](f: O => O2): MonixProSubject[I, O2] = MonixProSubject.from(self, self.map(f))
    def mapObserver[I2](f: I2 => I): MonixProSubject[I2, O] = MonixProSubject.from(self.contramap(f), self)
    def mapProSubject[I2, O2](write: I2 => I)(read: O => O2): MonixProSubject[I2, O2] = MonixProSubject.from(self.contramap(write), self.map(read))

    def collectObservable[O2](f: PartialFunction[O, O2]): MonixProSubject[I, O2] = MonixProSubject.from(self, self.collect(f))
    def collectObserver[I2](f: PartialFunction[I2, I]): MonixProSubject[I2, O] = MonixProSubject.from(self.redirectCollect(f), self)
    def collectProSubject[I2, O2](write: PartialFunction[I2, I])(read: PartialFunction[O, O2]): MonixProSubject[I2, O2] = MonixProSubject.from(self.redirectCollect(write), self.collect(read))

    def filterObservable(f: O => Boolean): MonixProSubject[I, O] = MonixProSubject.from(self, self.filter(f))
    def filterObserver(f: I => Boolean): MonixProSubject[I, O] = MonixProSubject.from(self.redirectFilter(f), self)
    def filterProSubject(write: I => Boolean)(read: O => Boolean): MonixProSubject[I, O] = MonixProSubject.from(self.redirectFilter(write), self.filter(read))

    def transformObservable[O2](f: Observable[O] => Observable[O2]): MonixProSubject[I,O2] = MonixProSubject.from(self, f(self))
    def transformObserver[I2](f: Observable[I2] => Observable[I]): MonixProSubject[I2,O] with ReactiveConnectable = MonixProSubject.connectable(self.redirect(f), self)
    def transformProSubject[I2, O2](write: Observable[I2] => Observable[I])(read: Observable[O] => Observable[O2]): MonixProSubject[I2,O2] with ReactiveConnectable = MonixProSubject.connectable(self.redirect(write), read(self))
  }

  implicit class RichSubject[T](val self: MonixSubject[T]) extends AnyVal {
    def lens[S](seed: T)(read: T => S)(write: (T, S) => T): MonixSubject[S] with ReactiveConnectable = {
      val redirected = self
        .redirect[S](_.withLatestFrom(self.startWith(Seq(seed))){ case (a, b) => write(b, a) })

      MonixProSubject.connectable(redirected, self.map(read))
    }

    def mapSubject[T2](write: T2 => T)(read: T => T2): MonixSubject[T2] = MonixProSubject.from(self.contramap(write), self.map(read))
    def collectSubject[T2](write: PartialFunction[T2, T])(read: PartialFunction[T, T2]): MonixSubject[T2] = MonixProSubject.from(self.redirectCollect(write), self.collect(read))
    def filterSubject(write: T => Boolean)(read: T => Boolean): MonixSubject[T] = MonixProSubject.from(self.redirectFilter(write), self.filter(read))
    def transformSubject[T2](write: Observable[T2] => Observable[T])(read: Observable[T] => Observable[T2]): MonixSubject[T2] with ReactiveConnectable = MonixProSubject.connectable(self.redirect(write), read(self))
  }
}
