package colibri.reactive

import colibri.{Observable, Cancelable}

trait LiveOwnerPlatform {
  @annotation.compileTimeOnly(
    "No implicit LiveOwner is available here! Wrap inside `Rx { <code> }`, or provide an implicit `LiveOwner`.",
  )
  implicit object compileTimeMock extends LiveOwner {
    def cancelable: Cancelable          = ???
    def unsafeNow[A](rx: Rx[A]): A      = ???
    def unsafeLive[A](rx: Rx[A]): A     = ???
    def liveObservable: Observable[Any] = ???
  }
}
