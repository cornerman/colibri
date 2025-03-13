package colibri.effect

import cats.effect.SyncIO
import colibri.Cancelable

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

private[colibri] object RunEffectExecution {
  def handleFutureCancelable[T](future: Future[T], cancelRun: () => Future[Any])(cb: Either[Throwable, T] => Unit): Cancelable = {
    var isCancel = false

    def action(value: Either[Throwable, T]): Unit = {
      if (!isCancel) {
        isCancel = true
        cb(value)
      }
    }

    future.onComplete(result => action(result.toEither))(ExecutionContext.parasitic)

    Cancelable.withIsEmpty(isCancel) { () =>
      isCancel = true
      cancelRun(): Unit
    }
  }

  def handleSyncStepCancelable[F[_], T](syncStep: SyncIO[Either[F[T], T]], asyncStep: F[T] => Cancelable)(
      cb: Either[Throwable, T] => Unit,
  ): Cancelable = {
    try {
      syncStep.unsafeRunSync() match {
        case Left(effect)       =>
          asyncStep(effect)
        case right: Right[_, T] =>
          cb(right.asInstanceOf[Right[Nothing, T]])
          Cancelable.empty
      }
    } catch {
      case NonFatal(error) =>
        cb(Left(error))
        Cancelable.empty
    }
  }
}
