package colibri.reactive

import colibri._

trait NowOwner  {
  def unsafeNow[A](rx: Rx[A]): A
}
object NowOwner {
  implicit object global extends NowOwner {
    def unsafeNow[A](rx: Rx[A]): A = {
      val cancelable = rx.unsafeSubscribe()
      try (rx.nowIfSubscribed())
      finally (cancelable.unsafeCancel())
    }
  }
}

@annotation.implicitNotFound(
  "No implicit LiveOwner is available here! Wrap inside `Rx { <code> }`, or provide an implicit `LiveOwner`.",
)
trait LiveOwner  extends NowOwner          {
  def cancelable: Cancelable

  def liveObservable: Observable[Any]

  def unsafeLive[A](rx: Rx[A]): A
}
object LiveOwner extends LiveOwnerPlatform {
  def unsafeHotRef(): LiveOwner = new LiveOwner {
    private val ref = Cancelable.builder()

    private val subject = Subject.publish[Any]()

    val cancelable: Cancelable = ref

    val liveObservable: Observable[Any] = subject

    def unsafeNow[A](rx: Rx[A]): A = {
      ref.unsafeAdd(() => rx.observable.unsafeSubscribe())
      rx.nowIfSubscribed()
    }

    def unsafeLive[A](rx: Rx[A]): A = {
      ref.unsafeAdd(() => rx.observable.unsafeSubscribe(subject))
      rx.nowIfSubscribed()
    }
  }
}
