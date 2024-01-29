package colibri.effect

import cats.effect.std.Dispatcher
import cats.effect.{Async, IO, Resource, SyncIO, unsafe}
import colibri.Cancelable

trait RunEffect[-F[_]] {
  def unsafeRunAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable
  def unsafeRunSyncOrAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable
}

trait RunEffectLowPrio {
  implicit val IORunEffect: RunEffect[IO] = new RunEffectIOWithRuntime(unsafe.IORuntime.global)
}
object RunEffect extends RunEffectLowPrio {
  @inline def apply[F[_]](implicit run: RunEffect[F]): RunEffect[F] = run

  def forAsync[F[_]: Async]: Resource[F, RunEffect[F]] = Dispatcher.parallel[F].map(forDispatcher(_))

  def forDispatcher[F[_]: Async](dispatcher: Dispatcher[F]): RunEffect[F] = new RunEffectAsyncWithDispatcher(dispatcher)

  @inline implicit def IORunEffectRuntime(implicit ioRuntime: unsafe.IORuntime): RunEffect[IO] = new RunEffectIOWithRuntime(ioRuntime)

  @inline implicit def RunSyncEffectRunEffect[F[_]: RunSyncEffect]: RunEffect[F] = RunSyncEffect[F]
}

private final class RunEffectAsyncWithDispatcher[F[_]: Async](dispatcher: Dispatcher[F]) extends RunEffect[F] {
  override def unsafeRunAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val (future, cancelRun) = dispatcher.unsafeToFutureCancelable(effect)
    RunEffectExecution.handleFutureCancelable(future, cancelRun)(cb)
  }

  override def unsafeRunSyncOrAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val syncStep = Async[F].syncStep[SyncIO, T](effect, Int.MaxValue)
    RunEffectExecution.handleSyncStepCancelable[F, T](syncStep, x => unsafeRunAsyncCancelable(x)(cb))(cb)
  }

}

private final class RunEffectIOWithRuntime(ioRuntime: unsafe.IORuntime) extends RunEffect[IO] {
  override def unsafeRunAsyncCancelable[T](effect: IO[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val (future, cancelRun) = effect.unsafeToFutureCancelable()(ioRuntime)
    RunEffectExecution.handleFutureCancelable(future, cancelRun)(cb)
  }

  override def unsafeRunSyncOrAsyncCancelable[T](effect: IO[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val syncStep = effect.syncStep(Int.MaxValue)
    RunEffectExecution.handleSyncStepCancelable[IO, T](syncStep, x => unsafeRunAsyncCancelable(x)(cb))(cb)
  }
}
