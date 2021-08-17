package colibri

import cats.effect.Effect
import colibri.effect._
import scala.concurrent.{ExecutionContext, Future}

trait ObservableLike[-F[_]] {
  def toObservable[A](source: F[A]): Observable[A]
}
object ObservableLike {
  @inline def apply[F[_]](implicit like: ObservableLike[F]): ObservableLike[F] = like

  implicit def observableSource[F[_] : Source]: ObservableLike[F] = new ObservableLike[F] {
    def toObservable[A](source: F[A]): Observable[A] = Observable.lift(source)
  }

  implicit def observableEffect[F[_] : Effect]: ObservableLike[F] = new ObservableLike[F] {
    def toObservable[A](effect: F[A]): Observable[A] = Observable.fromAsync(effect)
  }

  implicit def observableSyncEffect[F[_] : RunSyncEffect]: ObservableLike[F] = new ObservableLike[F] {
    def toObservable[A](effect: F[A]): Observable[A] = Observable.fromSync(effect)
  }

  implicit def observableFuture(implicit ec: ExecutionContext): ObservableLike[Future] = new ObservableLike[Future] {
    def toObservable[A](future: Future[A]): Observable[A] = Observable.fromFuture(future)
  }
}
