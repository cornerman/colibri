package colibri

import cats.Monoid
import cats.effect.{Sync, SyncEffect}
import cats.implicits._
import cats.syntax.all._
import colibri.effect.RunSyncEffect

import scala.scalajs.js

trait Cancelable {
  def cancel[F[_] : Sync](): F[Unit]
}
object Cancelable {

  implicit object monoid extends Monoid[Cancelable] {
    @inline def empty = Cancelable.empty
    @inline def combine(a: Cancelable, b: Cancelable) = Cancelable.composite(a, b)
  }

  implicit object cancelCancelable extends CanCancel[Cancelable] {
    @inline def cancel[F[_] : Sync](subscription: Cancelable): F[Unit] = subscription.cancel()
  }

  class Builder extends Cancelable {
    private var buffer = new js.Array[Cancelable]()

    def +=[F[_] : Sync : RunSyncEffect](subscription: Cancelable): Unit =
      if (buffer == null) {
        RunSyncEffect[F].unsafeRun(subscription.cancel())
      } else {
        buffer.push(subscription)
        ()
      }

    def cancel[F[_] : Sync](): F[Unit] = (
        buffer.toList.traverse_(_.cancel()) *>
        { buffer = null }.pure[F]
      ).whenA(buffer != null)
  }

  class Variable extends Cancelable {
    private var current: Cancelable = Cancelable.empty

    def update[F[_] : Sync : RunSyncEffect](subscription: Cancelable): Unit =
      if (current == null) {
        RunSyncEffect[F].unsafeRun(subscription.cancel())
      } else {
        RunSyncEffect[F].unsafeRun(current.cancel())
        current = subscription
      }

    def cancel[F[_] : Sync](): F[Unit] =
      current.cancel() *>
      { current = null }.pure[F]
  }

  class Consecutive extends Cancelable {
    private var latest: Cancelable = null
    private var subscriptions: js.Array[() => Cancelable] = new js.Array[() => Cancelable]

    def switch[F[_] : Sync : RunSyncEffect](): Unit = if (latest != null) {
      RunSyncEffect[F].unsafeRun(latest.cancel())
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

    def +=[F[_] : Sync : RunSyncEffect](subscription: () => Cancelable): Unit = if (subscriptions != null) {
      if (latest == null) {
        val variable = Cancelable.variable()
        latest = variable
        variable() = subscription()
      } else {
        subscriptions.push(subscription)
        ()
      }
    }

    def cancel[F[_] : Sync](): F[Unit] = (
      { subscriptions = null }.pure[F] *>
        (
          latest.cancel() *>
          { latest = null }.pure[F]
        ).whenA(latest != null)
    ).whenA(subscriptions != null)
  }

  class SingleOrDrop extends Cancelable {
    private var latest: Cancelable = null
    private var isCancel = false

    def done[F[_] : Sync : RunSyncEffect](): Unit = if (latest != null) {
      RunSyncEffect[F].unsafeRun(latest.cancel())
      latest = null
    }

    def update[F[_] : Sync : RunSyncEffect](subscription: () => Cancelable): Unit = if (latest == null) {
      val variable = Cancelable.variable()
      latest = variable
      variable() = subscription()
    }

    def cancel[F[_] : Sync](): F[Unit] = (
      { isCancel = true }.pure[F] *>
      (
        latest.cancel() *>
        { latest = null }.pure[F]
      ).whenA(latest != null)
    ).whenA(!isCancel)
  }

  class RefCount(subscription: () => Cancelable) extends Cancelable {
    private var counter = 0
    private var currentCancelable: Cancelable = null

    def ref[F[_] : Sync : SyncEffect : RunSyncEffect](): Cancelable = if (counter == -1) Cancelable.empty else {
      counter += 1
      if (counter == 1) {
        currentCancelable = subscription()
      }

      Cancelable({ () => Sync[F].delay {
        counter -= 1
        if (counter == 0) {
          RunSyncEffect[F].unsafeRun(currentCancelable.cancel())
          currentCancelable = null
        }
      }})
    }

    def cancel[F[_] : Sync](): F[Unit] =
      { counter = -1 }.pure[F] *>
        (
          currentCancelable.cancel() *>
          { currentCancelable = null }.pure[F]
        ).whenA(currentCancelable != null)
  }

  object Empty extends Cancelable {
    def cancel[F[_] : Sync](): F[Unit] = Sync[F].delay(())
  }

  @inline def empty: Cancelable = Empty

  @inline def apply[F[_] : SyncEffect](f: () => F[Unit]): Cancelable = new Cancelable {
    private var isCanceled = false
    @inline def cancel[FF[_] : Sync](): FF[Unit] = (
      { isCanceled = true }.pure[FF] *>
      SyncEffect[F].runSync[FF, Unit](f())
    ).whenA(!isCanceled)
  }

  @inline def lift[F[_] : Sync : SyncEffect : RunSyncEffect, T : CanCancel](subscription: T) = apply(() => CanCancel[T].cancel(subscription))

  @inline def composite(subscriptions: Cancelable*): Cancelable = compositeFromIterable(subscriptions)
  @inline def compositeFromIterable(subscriptions: Iterable[Cancelable]): Cancelable = new Cancelable {
    def cancel[F[_] : Sync](): F[Unit] =
      subscriptions.toList.traverse_(cancelable => cancelable.cancel())
  }

  @inline def builder(): Builder = new Builder

  @inline def variable(): Variable = new Variable

  @inline def consecutive(): Consecutive = new Consecutive

  @inline def singleOrDrop(): SingleOrDrop = new SingleOrDrop

  @inline def refCount(subscription: () => Cancelable): RefCount = new RefCount(subscription)
}
