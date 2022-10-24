package colibri

import cats.Applicative
import colibri.helpers.Newtype1

// Inspired by monix: https://github.com/monix/monix/blob/88fdaf67a11bffb8bf7a2d525f7564491574743e/monix-reactive/shared/src/main/scala/monix/reactive/observables/CombineObservable.scala
object CombineObservable extends Newtype1[Observable] {
  implicit val applicative: Applicative[CombineObservable.Type] = new Applicative[CombineObservable.Type] {

    override def pure[A](x: A): colibri.CombineObservable.Type[A] =
      wrap(Observable.pure(x))

    override def ap[A, B](ff: CombineObservable.Type[(A) => B])(fa: CombineObservable.Type[A]) =
      wrap(unwrap(ff).combineLatestMap(unwrap(fa))((f, a) => f(a)))

    override def map[A, B](fa: CombineObservable.Type[A])(f: A => B): CombineObservable.Type[B] =
      wrap(unwrap(fa).map(f))

    override def map2[A, B, C](fa: CombineObservable.Type[A], fb: CombineObservable.Type[B])(f: (A, B) => C): CombineObservable.Type[C] =
      wrap(unwrap(fa).combineLatestMap(unwrap(fb))(f))

    override def product[A, B](fa: CombineObservable.Type[A], fb: CombineObservable.Type[B]): CombineObservable.Type[(A, B)] =
      wrap(unwrap(fa).combineLatest(unwrap(fb)))
  }
}
