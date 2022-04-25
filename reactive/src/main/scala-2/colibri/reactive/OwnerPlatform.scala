package colibri.reactive

import colibri.{Observable, Cancelable}

import scala.scalajs.js

trait OwnerPlatform {
  @annotation.compileTimeOnly(
    "No implicit Owner is available here! Wrap inside `Owned { <code> }`, or provide an implicit `Owner`, or `import Owner.unsafeImplicits._` (dangerous).",
  )
  implicit object compileTimeMock extends Owner {
    def unsafeSubscribe(): Cancelable                        = ???
    def cancelable: Cancelable                               = ???
    def unsafeOwn(subscription: () => Cancelable): Unit      = ???
    def unsafeOwnLater(subscription: () => Cancelable): Unit = ???
  }
}

trait LiveOwnerPlatform {
  @annotation.compileTimeOnly(
    "No implicit LiveOwner is available here! Wrap inside `Rx { <code> }`, or provide an implicit `LiveOwner`.",
  )
  implicit object compileTimeMock extends LiveOwner {
    def unsafeSubscribe(): Cancelable                        = ???
    def cancelable: Cancelable                               = ???
    def unsafeOwn(subscription: () => Cancelable): Unit      = ???
    def unsafeOwnLater(subscription: () => Cancelable): Unit = ???
    def unsafeLive[A](rx: Rx[A]): A                          = ???
    def unsafeLiveRxArray: js.Array[Rx[Any]]                      = ???
    def liveObservable: Observable[Any]                      = ???
  }
}
