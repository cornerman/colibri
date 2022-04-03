package colibri.reactive

import colibri._

trait Owner  {
  def unsafeSubscribe(): Cancelable
  def own(cancelable: () => Cancelable): Unit
}
object Owner {

  def ref(): Owner = new Owner {
    val refCountBuilder                           = Cancelable.refCountBuilder()
    def unsafeSubscribe(): Cancelable             = refCountBuilder.ref()
    def own(subscription: () => Cancelable): Unit = refCountBuilder.add(subscription)
  }

  def unsafeHotRef(): Owner = new Owner {
    val refCountBuilder = Cancelable.refCountBuilder()
    var initialRef      = refCountBuilder.ref()

    def unsafeSubscribe(): Cancelable = if (initialRef == null) {
      refCountBuilder.ref()
    } else {
      val result = initialRef
      initialRef = null
      result
    }

    def own(subscription: () => Cancelable): Unit = refCountBuilder.add(subscription)
  }
}
