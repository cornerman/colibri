package colibri.effect

import colibri.Cancelable
import cats.effect.{unsafe, IO, Async, Resource}
import cats.effect.std.Dispatcher

import scala.util.control.NonFatal

trait RunEffect[-F[_]] {
  def unsafeRunAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable
  def unsafeRunSyncOrAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable
}

trait RunEffectLowPrio {
  implicit val IORunEffect: RunEffect[IO] = new RunEffectIOWithRuntime(unsafe.IORuntime.global)
}
object RunEffect extends RunEffectLowPrio {
  @inline def apply[F[_]](implicit run: RunEffect[F]): RunEffect[F] = run

  def forAsync[F[_]: Async]: Resource[F, RunEffect[F]] = Dispatcher[F].map(forDispatcher(_))

  def forDispatcher[F[_]](dispatcher: Dispatcher[F]): RunEffect[F] = new RunEffectAsyncWithDispatcher(dispatcher)

  @inline implicit def IORunEffectRuntime(implicit ioRuntime: unsafe.IORuntime): RunEffect[IO] = new RunEffectIOWithRuntime(ioRuntime)

  @inline implicit def RunSyncEffectRunEffect[F[_]: RunSyncEffect]: RunEffect[F] = RunSyncEffect[F]
}

private final class RunEffectAsyncWithDispatcher[F[_]](dispatcher: Dispatcher[F]) extends RunEffect[F] {
  override def unsafeRunAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val (future, cancelRun) = dispatcher.unsafeToFutureCancelable(effect)
    RunEffectExecution.handleFutureCancelable(future, cancelRun)(cb)
  }

  // TODO: syncStep will be available for Async[F] in cats-effect 3.4.x
  override def unsafeRunSyncOrAsyncCancelable[T](effect: F[T])(cb: Either[Throwable, T] => Unit): Cancelable =
    unsafeRunAsyncCancelable(effect)(cb)
}

private final class RunEffectIOWithRuntime(ioRuntime: unsafe.IORuntime) extends RunEffect[IO] {
  override def unsafeRunAsyncCancelable[T](effect: IO[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    val (future, cancelRun) = effect.unsafeToFutureCancelable()(ioRuntime)
    RunEffectExecution.handleFutureCancelable(future, cancelRun)(cb)
  }

  override def unsafeRunSyncOrAsyncCancelable[T](effect: IO[T])(cb: Either[Throwable, T] => Unit): Cancelable = {
    try {
      effect.syncStep.unsafeRunSync() match {
        case Left(io)           =>
          unsafeRunAsyncCancelable(io)(cb)
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
