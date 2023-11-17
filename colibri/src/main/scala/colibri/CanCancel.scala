package colibri

trait CanCancel[-T] {
  def unsafeCancel(cancelable: T): Unit
}
object CanCancel    {
  @inline def apply[T](implicit unsafeCancel: CanCancel[T]): CanCancel[T] = unsafeCancel
}
