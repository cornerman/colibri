package colibri.reactive

import colibri.effect.SyncEmbed
import colibri.SubscriptionOwner

object Owned extends OwnedPlatform {
  def function[R: SubscriptionOwner: SyncEmbed](f: Owner => R): R = SyncEmbed[R].delay {
    val owner  = Owner.unsafeHotRef()
    val result = f(owner)
    SubscriptionOwner[R].own(result)(owner.unsafeSubscribe)
  }
}
