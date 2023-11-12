package colibri.effect

import colibri.Cancelable
import scala.concurrent.{Future, ExecutionContext}

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
      cancelRun()
      ()
    }
  }
}
