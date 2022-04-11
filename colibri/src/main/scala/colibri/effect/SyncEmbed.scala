package colibri.effect

import cats.effect.Sync

trait SyncEmbed[T] {
  // This always has to delay execution of body.
  // The trivial implementation would be wrong: def delay(body: => T): T = body
  def delay(body: => T): T
}
object SyncEmbed   {
  @inline def apply[T](implicit sync: SyncEmbed[T]): SyncEmbed[T] = sync

  @inline implicit def forSync[F[_]: Sync, T]: SyncEmbed[F[T]] = new SyncEmbed[F[T]] {
    @inline def delay(body: => F[T]): F[T] = Sync[F].defer(body)
  }
}
