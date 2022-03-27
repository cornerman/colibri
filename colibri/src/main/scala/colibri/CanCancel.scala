package colibri

trait CanCancel[-T] {
  def unsafeCancel(cancelable: T): Unit

  @deprecated("Use unsafeCancel instead", "0.2.7")
  @inline final def cancel(cancelable: T): Unit = unsafeCancel(cancelable)
}
object CanCancel    {
  @inline def apply[T](implicit unsafeCancel: CanCancel[T]): CanCancel[T] = unsafeCancel
}
