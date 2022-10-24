package colibri

import cats.Monoid
import cats.effect.{IO, Sync, SyncIO}
import cats.implicits._

import scala.scalajs.js

trait Cancelable  {
  def isEmpty(): Boolean
  def unsafeCancel(): Unit

  final def cancelF[F[_]: Sync]: F[Unit] = Sync[F].delay(unsafeCancel())
  final def cancelIO: IO[Unit]           = cancelF[IO]
  final def cancelSyncIO: SyncIO[Unit]   = cancelF[SyncIO]
}
object Cancelable {

  implicit object monoid extends Monoid[Cancelable] {
    @inline def empty                                 = Cancelable.empty
    @inline def combine(a: Cancelable, b: Cancelable) = Cancelable.composite(a, b)
  }

  implicit object unsafeCancelCancelable extends CanCancel[Cancelable] {
    @inline def unsafeCancel(subscription: Cancelable): Unit = subscription.unsafeCancel()
  }

  trait Setter extends Cancelable {
    def unsafeAdd(subscription: () => Cancelable): Unit
    def unsafeAddExisting(subscription: Cancelable): Unit
    def unsafeFreeze(): Unit
  }

  class Builder extends Setter {
    private var isFrozen = false
    private var buffer   = new js.Array[Cancelable]()

    def isEmpty() = buffer == null || isFrozen && buffer.forall(_.isEmpty())

    def unsafeAddExisting(subscription: Cancelable): Unit =
      if (buffer == null) subscription.unsafeCancel()
      else unsafeAdd(() => subscription)

    def unsafeAdd(subscription: () => Cancelable): Unit = if (buffer != null) {
      val cancelable = subscription()
      buffer.push(cancelable)
      ()
    }

    def unsafeFreeze(): Unit = {
      isFrozen = true
    }

    def unsafeCancel(): Unit =
      if (buffer != null) {
        buffer.foreach(_.unsafeCancel())
        buffer = null
      }
  }

  class Variable extends Setter {
    private var isFrozen            = false
    private var current: Cancelable = Cancelable.empty

    def isEmpty() = current == null || isFrozen && current.isEmpty()

    def unsafeAddExisting(subscription: Cancelable): Unit =
      if (current == null) subscription.unsafeCancel()
      else unsafeAdd(() => subscription)

    def unsafeAdd(subscription: () => Cancelable): Unit = if (current != null) {
      current.unsafeCancel()

      var isCancel = false
      current = Cancelable { () =>
        isCancel = true
      }

      val cancelable = subscription()
      if (isCancel) cancelable.unsafeCancel()
      else current = cancelable
    }

    def unsafeFreeze(): Unit = {
      isFrozen = true
    }

    def unsafeCancel(): Unit = if (current != null) {
      current.unsafeCancel()
      current = null
    }
  }

  class Consecutive extends Cancelable {
    private var isFrozen                                  = false
    private var latest: Cancelable                        = null
    private var subscriptions: js.Array[() => Cancelable] = new js.Array[() => Cancelable]

    def isEmpty() = subscriptions == null || isFrozen && (latest == null || latest.isEmpty()) && subscriptions.isEmpty

    def switch(): Unit = if (latest != null) {
      latest.unsafeCancel()
      latest = null
      if (subscriptions != null && subscriptions.nonEmpty) {
        val nextCancelable = subscriptions(0)
        val variable       = Cancelable.variable()
        latest = variable
        subscriptions.splice(0, deleteCount = 1)
        variable.unsafeAdd(nextCancelable)
        variable.unsafeFreeze()
        ()
      }
    }

    def unsafeFreeze(): Unit = {
      isFrozen = true
    }

    def unsafeAdd(subscription: () => Cancelable): Unit = if (subscriptions != null) {
      if (latest == null) {
        val variable = Cancelable.variable()
        latest = variable
        variable.unsafeAdd(subscription)
        variable.unsafeFreeze()
      } else {
        subscriptions.push(subscription)
        ()
      }
    }

    def unsafeCancel(): Unit = if (subscriptions != null) {
      subscriptions = null
      if (latest != null) {
        latest.unsafeCancel()
        latest = null
      }
    }
  }

  class SingleOrDrop extends Cancelable {
    private var isFrozen           = false
    private var latest: Cancelable = null
    private var isCancel           = false

    def isEmpty() = isCancel || isFrozen && (latest == null || latest.isEmpty())

    def done(): Unit = if (latest != null) {
      latest.unsafeCancel()
      latest = null
    }

    def unsafeAdd(subscription: () => Cancelable): Unit = if (latest == null) {
      val variable = Cancelable.variable()
      latest = variable
      variable.unsafeAdd(subscription)
      variable.unsafeFreeze()
    }

    def unsafeFreeze(): Unit = {
      isFrozen = true
    }

    def unsafeCancel(): Unit = if (!isCancel) {
      isCancel = true
      if (latest != null) {
        latest.unsafeCancel()
        latest = null
      }
    }
  }

  class RefCount(subscription: () => Cancelable) extends Cancelable {
    private var isFrozen                      = false
    private var counter                       = 0
    private var currentCancelable: Cancelable = null

    def isEmpty() = counter == -1 || isFrozen && (currentCancelable == null || currentCancelable.isEmpty())

    def ref(): Cancelable = if (counter == -1) Cancelable.empty
    else {
      counter += 1
      if (counter == 1) {
        currentCancelable = subscription()
      }

      Cancelable { () =>
        counter -= 1
        if (counter == 0) {
          currentCancelable.unsafeCancel()
          currentCancelable = null
        }
      }
    }

    def unsafeFreeze(): Unit = {
      isFrozen = true
    }

    def unsafeCancel(): Unit = {
      counter = -1
      if (currentCancelable != null) {
        currentCancelable.unsafeCancel()
        currentCancelable = null
      }
    }
  }

  class RefCountBuilder extends Cancelable {
    private var isFrozen                                 = false
    private var counter                                  = 0
    private var buffer                                   = new js.Array[() => Cancelable]()
    private var currentCancelables: js.Array[Cancelable] = null

    def isEmpty() = buffer == null || isFrozen && (currentCancelables == null || currentCancelables.forall(_.isEmpty()))

    def ref(): Cancelable = if (counter == -1) Cancelable.empty
    else {
      counter += 1
      if (counter == 1) {
        currentCancelables = buffer.map(_())
      }

      Cancelable { () =>
        counter -= 1
        if (counter == 0) {
          currentCancelables.foreach(_.unsafeCancel())
          currentCancelables = null
        }
      }
    }

    def unsafeAdd(subscription: () => Cancelable): Unit = if (buffer != null) {
      buffer.push(subscription)
      if (currentCancelables != null) {
        currentCancelables.push(subscription())
      }
      ()
    }

    def unsafeFreeze(): Unit = {
      isFrozen = true
    }

    def unsafeCancel(): Unit = {
      counter = -1
      buffer = null
      if (currentCancelables != null) {
        currentCancelables.foreach(_.unsafeCancel())
        currentCancelables = null
      }
    }
  }

  object Empty extends Cancelable {
    @inline def isEmpty()            = true
    @inline def unsafeCancel(): Unit = ()
  }

  @inline def empty: Cancelable = Empty

  def apply(f: () => Unit): Cancelable = withIsEmpty(false)(f)

  def checkIsEmpty(empty: => Boolean)(f: () => Unit): Cancelable = new Cancelable {
    private var isDone = false

    def isEmpty() =
      if (isDone) true
      else if (empty) {
        isDone = true
        f()
        true
      } else false

    def unsafeCancel() = ()
  }

  def ignoreIsEmptyWrap(cancelable: Cancelable): Cancelable = ignoreIsEmpty(cancelable.unsafeCancel)
  def ignoreIsEmpty(f: () => Unit): Cancelable              = withIsEmpty(true)(f)

  def withIsEmptyWrap(empty: => Boolean)(cancelable: Cancelable): Cancelable = withIsEmpty(empty)(cancelable.unsafeCancel)
  def withIsEmpty(empty: => Boolean)(f: () => Unit): Cancelable              = new Cancelable {
    private var isCancel = false

    def isEmpty() = isCancel || empty

    def unsafeCancel() = if (!isCancel) {
      isCancel = true
      f()
    }
  }

  def lift[T: CanCancel](subscription: T): Cancelable = subscription match {
    case cancelable: Cancelable => cancelable
    case _                      => apply(() => CanCancel[T].unsafeCancel(subscription))
  }

  @inline def composite(subscriptions: Cancelable*): Cancelable              = compositeFromIterable(subscriptions)
  def compositeFromIterable(subscriptions: Iterable[Cancelable]): Cancelable = new Cancelable {
    def isEmpty()      = subscriptions.forall(_.isEmpty())
    def unsafeCancel() = subscriptions.foreach(_.unsafeCancel())
  }

  @inline def builder(): Builder = new Builder

  @inline def variable(): Variable = new Variable

  @inline def consecutive(): Consecutive = new Consecutive

  @inline def singleOrDrop(): SingleOrDrop = new SingleOrDrop

  @inline def refCount(subscription: () => Cancelable): RefCount = new RefCount(subscription)

  @inline def refCountBuilder(): RefCountBuilder = new RefCountBuilder
}
