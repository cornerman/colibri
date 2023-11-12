package colibri.effect

import cats.Eval
import cats.implicits._
import cats.effect.SyncIO
import colibri.Cancelable
import org.scalajs.macrotaskexecutor.MacrotaskExecutor

trait RunSyncEffect[-F[_]] extends RunEffect[F] {
  def unsafeRun[T](effect: F[T]): Either[Throwable, T]

  final override def unsafeRunAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    var isCancel = false

    MacrotaskExecutor.execute { () =>
      if (!isCancel) {
        isCancel = true
        val result = unsafeRun(effect)
        cb(result)
      }
    }

    Cancelable.withIsEmpty(isCancel) { () =>
      isCancel = true
    }
  }

  final override def unsafeRunSyncOrAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val result = unsafeRun(effect)
    cb(result)
    Cancelable.empty
  }
}

object RunSyncEffect {
  @inline def apply[F[_]](implicit run: RunSyncEffect[F]): RunSyncEffect[F] = run

  implicit object syncIO extends RunSyncEffect[SyncIO] {
    @inline def unsafeRun[T](effect: SyncIO[T]) = Either.catchNonFatal(effect.unsafeRunSync())
  }

  implicit object eval extends RunSyncEffect[Eval] {
    @inline def unsafeRun[T](effect: Eval[T]) = Right(effect.value)
  }
}
