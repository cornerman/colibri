package colibri.reactive

import colibri.reactive.internal.MacroUtils

trait RxPlatform {
  def apply[R](f: R): Rx[R] = macro MacroUtils.rxImpl[R]
}
