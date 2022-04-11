package colibri

import cats.Eval
import cats.effect.{Sync, Resource}
import colibri.effect._
import scala.concurrent.Future
import scala.util.Try

trait ObservableLike[-F[_]] {
  def toObservable[A](source: F[A]): Observable[A]
}
object ObservableLike       {
  @inline def apply[F[_]](implicit like: ObservableLike[F]): ObservableLike[F] = like

  implicit val fromEither: ObservableLike[Either[Throwable, *]] = new ObservableLike[Either[Throwable, *]] {
    def toObservable[A](either: Either[Throwable, A]): Observable[A] = Observable.fromEither(either)
  }

  implicit val fromTry: ObservableLike[Try] = new ObservableLike[Try] {
    def toObservable[A](value: Try[A]): Observable[A] = Observable.fromTry(value)
  }

  implicit val fromIterable: ObservableLike[Iterable] = new ObservableLike[Iterable] {
    def toObservable[A](iterable: Iterable[A]): Observable[A] = Observable.fromIterable(iterable)
  }

  implicit val fromFunction0: ObservableLike[Function0] = new ObservableLike[Function0] {
    def toObservable[A](function: Function0[A]): Observable[A] = Observable.eval(function())
  }

  implicit val fromEval: ObservableLike[Eval] = new ObservableLike[Eval] {
    def toObservable[A](eval: Eval[A]): Observable[A] = Observable.fromEval(eval)
  }

  implicit def fromSource[H[_]: Source]: ObservableLike[H] = new ObservableLike[H] {
    def toObservable[A](source: H[A]): Observable[A] = Observable.lift(source)
  }

  implicit def fromEffect[F[_]: RunEffect]: ObservableLike[F] = new ObservableLike[F] {
    def toObservable[A](effect: F[A]): Observable[A] = Observable.fromEffect(effect)
  }

  implicit val fromFuture: ObservableLike[Future] = new ObservableLike[Future] {
    def toObservable[A](future: Future[A]): Observable[A] = Observable.fromFuture(future)
  }

  implicit def fromResource[F[_]: RunEffect: Sync]: ObservableLike[Resource[F, *]] = new ObservableLike[Resource[F, *]] {
    def toObservable[A](effect: Resource[F, A]): Observable[A] = Observable.fromResource(effect)
  }
}
