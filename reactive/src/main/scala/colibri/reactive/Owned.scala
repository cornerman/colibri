package colibri.reactive

import colibri.SubscriptionOwner
import cats.effect.SyncIO

object Owned extends OwnedPlatform {
  def function[R: SubscriptionOwner](f: Owner => R): SyncIO[R] = SyncIO {
    val owner  = Owner.unsafeHotRef()
    val result = f(owner)
    SubscriptionOwner[R].own(result)(owner.unsafeSubscribe)
  }
}
