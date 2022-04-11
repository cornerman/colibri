package colibri.reactive

import colibri.reactive.internal.MacroUtils
import colibri.SubscriptionOwner
import cats.effect.SyncIO

trait OwnedPlatform {
  def apply[R: SubscriptionOwner](f: R): SyncIO[R] = macro MacroUtils.ownedImpl[R]
}
