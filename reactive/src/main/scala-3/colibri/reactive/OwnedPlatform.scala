package colibri.reactive

import colibri.SubscriptionOwner
import cats.effect.SyncIO

trait OwnedPlatform {
  def apply[R: SubscriptionOwner](f: Owner ?=> R): SyncIO[R] = Owned.function(implicit owner => f)
}
