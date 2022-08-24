package colibri.reactive

import colibri.effect.SyncEmbed
import colibri.SubscriptionOwner

trait OwnedPlatform {
  def apply[R: SubscriptionOwner: SyncEmbed](f: Owner ?=> R): R = Owned.function(implicit owner => f)
}
