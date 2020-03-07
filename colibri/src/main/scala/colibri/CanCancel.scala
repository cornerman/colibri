package colibri

trait CanCancel[-T] {
  def cancel(cancelable: T): Unit
}
object CanCancel {
  @inline def apply[T](implicit cancel: CanCancel[T]): CanCancel[T] = cancel
}
