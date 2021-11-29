package colibri

import cats.effect.Sync

trait CanCancel[-T] {
  def cancel[F[_] : Sync](cancelable: T): F[Unit]
}
object CanCancel {
  @inline def apply[T](implicit cancel: CanCancel[T]): CanCancel[T] = cancel
}
