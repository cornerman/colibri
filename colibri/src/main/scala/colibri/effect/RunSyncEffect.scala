package colibri.effect

import cats.Eval
import cats.implicits._
import cats.effect.SyncIO

trait RunSyncEffect[-F[_]] {
  def unsafeRun[T](effect: F[T]): Either[Throwable, T]
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
