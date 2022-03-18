package colibri.effect

import colibri.Cancelable
import scala.concurrent.{Future, ExecutionContext}

private[colibri] object RunEffectExecution {
  // ExecutionContext.parasitic only exists in scala 2.13. Not 2.12.
  private object parasitic extends ExecutionContext {
    override final def execute(runnable: Runnable): Unit = runnable.run()
    override final def reportFailure(t: Throwable): Unit = ExecutionContext.defaultReporter(t)
  }

  def handleFutureCancelable[T](future: Future[T], cancelRun: () => Future[Any], cb: Either[Throwable, T] => Unit): Cancelable = {
      var isCancel = false

      def action(value: Either[Throwable, T]): Unit = {
        if (!isCancel) {
          isCancel = true
          cb(value)
        }
      }

      future.onComplete(result => action(result.toEither))(parasitic)

      Cancelable { () =>
        isCancel = true
        cancelRun()
        ()
      }
  }
}
