package colibri.reactive

import colibri.{Observer, Observable}

object implicits {
  @inline class ObservableRxOps[A](private val self: Observable[A]) extends AnyVal {
    def foreachOwned(f: A => Unit)(implicit owner: Owner): Unit =
      subscribeOwned(Observer.foreach(f))

    def subscribeOwned(rxWriter: RxWriter[A])(implicit owner: Owner): Unit =
      subscribeOwned(rxWriter.observer)

    def subscribeOwned(observer: Observer[A])(implicit owner: Owner): Unit =
      owner.unsafeOwn(() => self.unsafeSubscribe(observer))
  }
}
