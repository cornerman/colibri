package colibri.reactive

import colibri.reactive.internal.MacroUtils

trait RxPlatform {
  def apply[R](f: R)(implicit owner: Owner): Rx[R] = macro MacroUtils.rxImpl[R]
}
