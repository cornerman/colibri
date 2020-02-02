package colibri

import cats.Functor

// This typeclass represents the capability of managing a subscription
// in type T. That means, for any value of T, we can embed a subscription
// into this value and get a new T. That means the type T owns
// this subscription and decides when to subscribe and when to unsubscribe.

trait CancelableOwner[T] {
  def own(owner: T)(subscription: () => Cancelable): T
}
object CancelableOwner {
  @inline def apply[T](implicit owner: CancelableOwner[T]): CancelableOwner[T] = owner

  implicit def functorOwner[F[_] : Functor, T : CancelableOwner]: CancelableOwner[F[T]] = new CancelableOwner[F[T]] {
    def own(owner: F[T])(subscription: () => Cancelable): F[T] = Functor[F].map(owner)(owner => CancelableOwner[T].own(owner)(subscription))
  }
}
