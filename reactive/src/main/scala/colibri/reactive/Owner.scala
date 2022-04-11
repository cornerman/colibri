package colibri.reactive

import colibri._

@annotation.implicitNotFound(
  "No implicit Owner is available here! Wrap inside `Owned { <code> }` or provide an implicit `Owner` or `import Owner.unsafeImplicits._`.",
)
trait Owner {
  def cancelable: Cancelable
  def unsafeSubscribe(): Cancelable
  def own(subscription: () => Cancelable): Unit
}
object Owner extends OwnerPlatform {
  def unsafeHotRef(): Owner = new Owner {
    val refCountBuilder = Cancelable.refCountBuilder()
    var initialRef      = refCountBuilder.ref()

    def cancelable: Cancelable = refCountBuilder

    def unsafeSubscribe(): Cancelable = if (initialRef == null) {
      refCountBuilder.ref()
    } else {
      val result = initialRef
      initialRef = null
      result
    }

    def own(subscription: () => Cancelable): Unit = refCountBuilder.add(subscription)
  }

  object unsafeGlobal extends Owner {
    def cancelable: Cancelable                    = Cancelable.empty
    def unsafeSubscribe(): Cancelable             = Cancelable.empty
    def own(subscription: () => Cancelable): Unit = {
      subscription()
      ()
    }
  }

  object unsafeImplicits {
    implicit def unsafeGlobalOwner: Owner = unsafeGlobal
  }
}

@annotation.implicitNotFound(
  "No implicit LiveOwner is available here! Wrap inside `Rx { <code> }` or provide an implicit `LiveOwner`.",
)
trait LiveOwner  extends Owner             {
  def liveObservable: Observable[Any]
  def unsafeLive[A](rx: Rx[A]): A
}
object LiveOwner extends LiveOwnerPlatform {
  def unsafeHotRef()(implicit parentOwner: Owner): LiveOwner = new LiveOwner {
    val owner: Owner = Owner.unsafeHotRef()
    parentOwner.own(() => owner.unsafeSubscribe())

    val liveObservableArray             = new scala.scalajs.js.Array[Observable[Any]]()
    val liveObservable: Observable[Any] = Observable.mergeIterable(liveObservableArray)

    def unsafeLive[A](rx: Rx[A]): A = {
      liveObservableArray.push(rx.observable)
      rx.now()
    }

    def unsafeSubscribe(): Cancelable             = owner.unsafeSubscribe()
    def own(subscription: () => Cancelable): Unit = owner.own(subscription)
    def cancelable: Cancelable                    = owner.cancelable
  }
}
