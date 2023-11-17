package colibri

final class Connectable[+T] private (val value: T, val unsafeConnect: () => Cancelable) {
  def map[A](f: T => A): Connectable[A]                  = new Connectable(f(value), unsafeConnect)
  def flatMap[A](f: T => Connectable[A]): Connectable[A] = {
    val connectable = f(value)
    new Connectable(connectable.value, () => Cancelable.composite(unsafeConnect(), connectable.unsafeConnect()))
  }
}
object Connectable                                                                      {
  def apply[T](value: T, unsafeConnect: () => Cancelable) = {
    val cancelable = Cancelable.refCount(unsafeConnect)
    new Connectable(value, cancelable.ref)
  }

  @inline implicit class ConnectableObservableOperations[A](val source: Connectable[Observable[A]]) extends AnyVal {
    def refCount: Observable[A]    = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = Cancelable.composite(source.value.unsafeSubscribe(sink), source.unsafeConnect())
    }
    def unsafeHot(): Observable[A] = {
      val _ = source.unsafeConnect()
      source.value
    }
  }
}
