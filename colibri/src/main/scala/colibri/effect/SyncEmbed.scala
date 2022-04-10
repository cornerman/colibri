package colibri.effect

trait SyncEmbed[T] {
  // This always has to delay execution of body.
  // The trivial implementation would be wrong: def delay(body: => T): T = body
  def delay(body: => T): T
}
object SyncEmbed   {
  @inline def apply[T](implicit sync: SyncEmbed[T]): SyncEmbed[T] = sync
}
