package colibri

import cats.Functor
import cats.effect.Sync

// This typeclass represents the capability of managing a subscription
// in type T. That means, for any value of T, we can embed a subscription
// into this value and get a new T. That means the type T owns
// this subscription and decides when to subscribe and when to unsubscribe.

trait SubscriptionOwner[T] {
  def own(owner: T)(subscription: () => Cancelable): T
}
object SubscriptionOwner   {
  @inline def apply[T](implicit owner: SubscriptionOwner[T]): SubscriptionOwner[T] = owner

  implicit def syncCancelableOwner[F[_]: Sync]: SubscriptionOwner[F[Cancelable]] = new SubscriptionOwner[F[Cancelable]] {
    def own(owner: F[Cancelable])(subscription: () => Cancelable): F[Cancelable] =
      Sync[F].flatMap(owner)(owner => Sync[F].delay(Cancelable.composite(owner, subscription())))
  }

  implicit def functorOwner[F[_]: Functor, T: SubscriptionOwner]: SubscriptionOwner[F[T]] = new SubscriptionOwner[F[T]] {
    def own(owner: F[T])(subscription: () => Cancelable): F[T] =
      Functor[F].map(owner)(owner => SubscriptionOwner[T].own(owner)(subscription))
  }
}
