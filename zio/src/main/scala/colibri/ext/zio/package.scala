package colibri.ext

import colibri._
import colibri.effect._

import _root_.zio.{Task, Runtime, ZEnv}

trait ZioLowPrio {
  implicit val RunEffectZIO: RunEffect[Task] = new RunEffectZIOWithRuntime(Runtime.default)
}
package object zio extends ZioLowPrio {
  implicit def forZIO(implicit ioRuntime: Runtime[ZEnv]): RunEffect[Task] = new RunEffectZIOWithRuntime(ioRuntime)
}

private class RunEffectZIOWithRuntime(ioRuntime: Runtime[ZEnv]) extends RunEffect[Task] {
  override def unsafeRunAsyncCancelable[T](effect: Task[T], cb: Either[Throwable, T] => Unit): Cancelable = unsafeRunSyncOrAsyncCancelable(Task.yieldNow *> effect, cb)

  override def unsafeRunSyncOrAsyncCancelable[T](effect: Task[T], cb: Either[Throwable, T] => Unit): Cancelable = {
    val cancelableFuture = ioRuntime.unsafeRunToFuture(effect)
    RunEffectExecution.handleFutureCancelable(cancelableFuture, cancelableFuture.cancel, cb)
  }
}
