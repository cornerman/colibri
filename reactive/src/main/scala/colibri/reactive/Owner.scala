package colibri.reactive

import colibri._

import scala.scalajs.js

@annotation.implicitNotFound(
  "No implicit Owner is available here! Wrap inside `Owned { <code> }`, or provide an implicit `Owner`, or `import Owner.unsafeImplicits._` (dangerous).",
)
trait Owner {
  def cancelable: Cancelable
  def unsafeSubscribe(): Cancelable
  def unsafeOwn(subscription: () => Cancelable): Unit
  def unsafeOwnLater(subscription: () => Cancelable): Unit
}
object Owner extends OwnerPlatform {
  def unsafeHotRef(): Owner = new Owner {
    val refCountBuilder = Cancelable.refCountBuilder()

    def cancelable: Cancelable = refCountBuilder

    def unsafeSubscribe(): Cancelable = refCountBuilder.ref()

    def unsafeOwn(subscription: () => Cancelable): Unit = refCountBuilder.unsafeAdd(subscription)

    def unsafeOwnLater(subscription: () => Cancelable): Unit = refCountBuilder.unsafeAddLater(subscription)
  }

  object unsafeGlobal extends Owner {
    def cancelable: Cancelable                               = Cancelable.empty
    def unsafeSubscribe(): Cancelable                        = Cancelable.empty
    def unsafeOwn(subscription: () => Cancelable): Unit      = { val _ = subscription() }
    def unsafeOwnLater(subscription: () => Cancelable): Unit = ()
  }

  object unsafeImplicits {
    implicit def unsafeGlobalOwner: Owner = unsafeGlobal
  }
}

@annotation.implicitNotFound(
  "No implicit LiveOwner is available here! Wrap inside `Rx { <code> }`, or provide an implicit `LiveOwner`.",
)
trait LiveOwner  extends Owner             {
  def unsafeLiveRxArray: js.Array[Rx[Any]]
  def unsafeLive[A](rx: Rx[A]): A
}
object LiveOwner extends LiveOwnerPlatform {
  def unsafeHotRef(): LiveOwner = new LiveOwner {
    val owner: Owner = Owner.unsafeHotRef()

    val unsafeLiveRxArray = new js.Array[Rx[Any]]()

    def unsafeLive[A](rx: Rx[A]): A = {
      unsafeLiveRxArray.push(rx)
      rx.now()
    }

    def unsafeSubscribe(): Cancelable                        = owner.unsafeSubscribe()
    def unsafeOwn(subscription: () => Cancelable): Unit      = owner.unsafeOwn(subscription)
    def unsafeOwnLater(subscription: () => Cancelable): Unit = owner.unsafeOwnLater(subscription)
    def cancelable: Cancelable                               = owner.cancelable
  }
}
