package colibri

import cats.Monoid
import cats.effect.{IO, Sync, SyncIO}
import cats.implicits._

import scala.scalajs.js

trait Cancelable  {
  def unsafeCancel(): Unit

  @deprecated("Use unsafeCancel() instead", "0.2.7")
  @inline final def cancel(): Unit = unsafeCancel()

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
    def add(subscription: () => Cancelable): Unit
    def addExisting(subscription: Cancelable): Unit
  }

  class Builder extends Setter {
    private var buffer = new js.Array[Cancelable]()

    def addExisting(subscription: Cancelable): Unit =
      if (buffer == null) subscription.unsafeCancel()
      else add(() => subscription)

    def add(subscription: () => Cancelable): Unit = if (buffer != null) {
      val cancelable = subscription()
      buffer.push(cancelable)
      ()
    }

    def unsafeCancel(): Unit =
      if (buffer != null) {
        buffer.foreach(_.unsafeCancel())
        buffer = null
      }
  }

  class Variable extends Setter {
    private var current: Cancelable = Cancelable.empty

    def addExisting(subscription: Cancelable): Unit =
      if (current == null) subscription.unsafeCancel()
      else add(() => subscription)

    def add(subscription: () => Cancelable): Unit = if (current != null) {
      current.unsafeCancel()

      var isCancel = false
      current = Cancelable { () =>
        isCancel = true
      }

      val cancelable = subscription()
      if (isCancel) cancelable.unsafeCancel()
      else current = cancelable
    }

    def unsafeCancel(): Unit =
      if (current != null) {
        current.unsafeCancel()
        current = null
      }
  }

  class Consecutive extends Cancelable {
    private var latest: Cancelable                        = null
    private var subscriptions: js.Array[() => Cancelable] = new js.Array[() => Cancelable]

    def switch(): Unit = if (latest != null) {
      latest.unsafeCancel()
      latest = null
      if (subscriptions != null && subscriptions.nonEmpty) {
        val nextCancelable = subscriptions(0)
        val variable       = Cancelable.variable()
        latest = variable
        subscriptions.splice(0, deleteCount = 1)
        variable.add(nextCancelable)
        ()
      }
    }

    def add(subscription: () => Cancelable): Unit = if (subscriptions != null) {
      if (latest == null) {
        val variable = Cancelable.variable()
        latest = variable
        variable.add(subscription)
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
    private var latest: Cancelable = null
    private var isCancel           = false

    def done(): Unit = if (latest != null) {
      latest.unsafeCancel()
      latest = null
    }

    def add(subscription: () => Cancelable): Unit = if (latest == null) {
      val variable = Cancelable.variable()
      latest = variable
      variable.add(subscription)
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
    private var counter                       = 0
    private var currentCancelable: Cancelable = null

    def ref(): Cancelable = if (counter == -1) Cancelable.empty
    else {
      counter += 1
      if (counter == 1) {
        currentCancelable = subscription()
      }

      Cancelable({ () =>
        counter -= 1
        if (counter == 0) {
          currentCancelable.unsafeCancel()
          currentCancelable = null
        }
      })
    }

    def unsafeCancel(): Unit = {
      counter = -1
      if (currentCancelable != null) {
        currentCancelable.unsafeCancel()
        currentCancelable = null
      }
    }
  }

  object Empty extends Cancelable {
    @inline def unsafeCancel(): Unit = ()
  }

  @inline def empty = Empty

  @inline def apply(f: () => Unit): Cancelable = new Cancelable {
    private var isCanceled     = false
    @inline def unsafeCancel() = if (!isCanceled) {
      isCanceled = true
      f()
    }
  }

  @inline def lift[T: CanCancel](subscription: T): Cancelable = subscription match {
    case cancelable: Cancelable => cancelable
    case _                      => apply(() => CanCancel[T].unsafeCancel(subscription))
  }

  @inline def composite(subscriptions: Cancelable*): Cancelable              = compositeFromIterable(subscriptions)
  def compositeFromIterable(subscriptions: Iterable[Cancelable]): Cancelable = {
    val nonEmptySubscriptions = subscriptions.filter(_ != Cancelable.empty)
    if (nonEmptySubscriptions.isEmpty) Cancelable.empty
    else
      new Cancelable {
        def unsafeCancel() = nonEmptySubscriptions.foreach(_.unsafeCancel())
      }
  }

  @inline def builder(): Builder = new Builder

  @inline def variable(): Variable = new Variable

  @inline def consecutive(): Consecutive = new Consecutive

  @inline def singleOrDrop(): SingleOrDrop = new SingleOrDrop

  @inline def refCount(subscription: () => Cancelable): RefCount = new RefCount(subscription)
}
