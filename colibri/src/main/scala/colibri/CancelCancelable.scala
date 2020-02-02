package colibri

trait CancelCancelable[-T] {
  def cancel(subscription: T): Unit
}
object CancelCancelable {
  @inline def apply[T](implicit subscription: CancelCancelable[T]): CancelCancelable[T] = subscription
}
