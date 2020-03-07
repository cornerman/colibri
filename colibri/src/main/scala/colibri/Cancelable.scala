package colibri

import cats.Monoid

import scala.scalajs.js

trait Cancelable {
  def cancel(): Unit
}
object Cancelable {

  class Builder extends Cancelable {
    private var buffer = new js.Array[Cancelable]()

    def +=(subscription: Cancelable): Unit =
      if (buffer == null) {
        subscription.cancel()
      } else {
        buffer.push(subscription)
        ()
      }

    def cancel(): Unit =
      if (buffer != null) {
        buffer.foreach(_.cancel())
        buffer = null
      }
  }

  class Variable extends Cancelable {
    private var current: Cancelable = Cancelable.empty

    def update(subscription: Cancelable): Unit =
      if (current == null) {
        subscription.cancel()
      } else {
        current.cancel()
        current = subscription
      }

    def cancel(): Unit =
      if (current != null) {
        current.cancel()
        current = null
    }
  }

  class Consecutive extends Cancelable {
    private var latest: Cancelable = null
    private var subscriptions: js.Array[() => Cancelable] = new js.Array[() => Cancelable]

    def switch(): Unit = if (latest != null) {
      latest.cancel()
      latest = null
      if (subscriptions != null && subscriptions.nonEmpty) {
        val nextCancelable = subscriptions(0)
        val variable = Cancelable.variable()
        latest = variable
        subscriptions.splice(0, deleteCount = 1)
        variable() = nextCancelable()
        ()
      }
    }

    def +=(subscription: () => Cancelable): Unit = if (subscriptions != null) {
      if (latest == null) {
        val variable = Cancelable.variable()
        latest = variable
        variable() = subscription()
      } else {
        subscriptions.push(subscription)
        ()
      }
    }

    def cancel(): Unit = if (subscriptions != null) {
      subscriptions = null
      if (latest != null) {
        latest.cancel()
        latest = null
      }
    }
  }

  class RefCount(subscription: () => Cancelable) extends Cancelable {
    private var counter = 0
    private var currentCancelable: Cancelable = null

    def ref(): Cancelable = if (counter == -1) Cancelable.empty else {
      counter += 1
      if (counter == 1) {
        currentCancelable = subscription()
      }

      Cancelable({ () =>
        counter -= 1
        if (counter == 0) {
          currentCancelable.cancel()
          currentCancelable = null
        }
      })
    }

    def cancel(): Unit = {
      counter = -1
      if (currentCancelable != null) {
        currentCancelable.cancel()
        currentCancelable = null
      }
    }
  }

  object Empty extends Cancelable {
    @inline def cancel(): Unit = ()
  }

  @inline def empty = Empty

  @inline def apply(f: () => Unit) = new Cancelable {
    private var isCanceled = false
    @inline def cancel() = if (!isCanceled) {
      isCanceled = true
      f()
    }
  }

  @inline def lift[T : CanCancel](subscription: T) = apply(() => CanCancel[T].cancel(subscription))

  @inline def composite(subscriptions: Cancelable*): Cancelable = compositeFromIterable(subscriptions)
  @inline def compositeFromIterable(subscriptions: Iterable[Cancelable]): Cancelable = new Cancelable {
    def cancel() = subscriptions.foreach(_.cancel())
  }

  @inline def builder(): Builder = new Builder

  @inline def variable(): Variable = new Variable

  @inline def consecutive(): Consecutive = new Consecutive

  @inline def refCount(subscription: () => Cancelable): RefCount = new RefCount(subscription)

  implicit object monoid extends Monoid[Cancelable] {
    @inline def empty = Cancelable.empty
    @inline def combine(a: Cancelable, b: Cancelable) = Cancelable.composite(a, b)
  }

  implicit object cancelCancelable extends CanCancel[Cancelable] {
    @inline def cancel(subscription: Cancelable): Unit = subscription.cancel()
  }
}
