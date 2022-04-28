package colibri.reactive

import colibri.{Cancelable, SubscriptionOwner}
import cats.effect.SyncIO

object Owned extends OwnedPlatform {
  def function[R: SubscriptionOwner](f: Owner => R): SyncIO[R] = SyncIO {
    val owner               = Owner.unsafeRef()
    val initialSubscription = owner.unsafeSubscribe()
    val result              = f(owner)
    SubscriptionOwner[R].own(result)(() => Cancelable.composite(owner.unsafeSubscribe(), initialSubscription))
  }
}
