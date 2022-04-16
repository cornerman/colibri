package colibri.reactive

import colibri.{Observable, Cancelable}

trait OwnerPlatform {
  @annotation.compileTimeOnly(
    "No implicit Owner is available here! Wrap inside `Owned { <code> }` or provide an implicit `Owner` or `import Owner.unsafeImplicits._`.",
  )
  implicit object compileTimeMock extends Owner {
    def unsafeSubscribe(): Cancelable                   = ???
    def unsafeOwn(subscription: () => Cancelable): Unit = ???
    def cancelable: Cancelable                          = ???
  }
}

trait LiveOwnerPlatform {
  @annotation.compileTimeOnly(
    "No implicit LiveOwner is available here! Wrap inside `Rx { <code> }` or provide an implicit `LiveOwner`.",
  )
  implicit object compileTimeMock extends LiveOwner {
    def unsafeSubscribe(): Cancelable                   = ???
    def unsafeOwn(subscription: () => Cancelable): Unit = ???
    def cancelable: Cancelable                          = ???
    def unsafeLive[A](rx: Rx[A]): A                     = ???
    def liveObservable: Observable[Any]                 = ???
  }
}
