package colibri.reactive

import colibri.reactive.internal.MacroUtils
import colibri.effect.SyncEmbed
import colibri.SubscriptionOwner

trait OwnedPlatform {
  def apply[R: SubscriptionOwner: SyncEmbed](f: R): R = macro MacroUtils.ownedImpl[R]
}
