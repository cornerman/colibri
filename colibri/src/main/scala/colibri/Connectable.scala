package colibri

final class Connectable[+T] private (val value: T, val connect: () => Cancelable) {
  def map[A](f: T => A): Connectable[A]                  = new Connectable(f(value), connect)
  def flatMap[A](f: T => Connectable[A]): Connectable[A] = {
    val connectable = f(value)
    new Connectable(connectable.value, () => Cancelable.composite(connect(), connectable.connect()))
  }
}
object Connectable                                                                {
  def apply[T](value: T, connect: () => Cancelable) = {
    val cancelable = Cancelable.refCount(connect)
    new Connectable(value, cancelable.ref)
  }

  @inline implicit class ConnectableObservableOperations[A](val source: Connectable[Observable[A]]) extends AnyVal {
    def refCount: Observable[A]      = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = Cancelable.composite(source.value.unsafeSubscribe(sink), source.connect())
    }
    @deprecated("Use unsafeHot instead", "0.5.0")
    def hot: Observable.Hot[A]       = unsafeHot
    def unsafeHot: Observable.Hot[A] = new Observable.Hot[A] {
      val cancelable                                     = source.connect()
      def unsafeSubscribe(sink: Observer[A]): Cancelable = source.value.unsafeSubscribe(sink)
    }
  }

  @inline implicit class ConnectableObservableValueOperations[A](val source: Connectable[Observable.Value[A]]) extends AnyVal {
    def refCount: Observable.Value[A] = new Observable.Value[A] {
      def now()                                          = source.value.now()
      def unsafeSubscribe(sink: Observer[A]): Cancelable = Cancelable.composite(source.value.unsafeSubscribe(sink), source.connect())
    }
    def hot: Observable.HotValue[A]   = new Observable.HotValue[A] {
      val cancelable                                     = source.connect()
      def now()                                          = source.value.now()
      def unsafeSubscribe(sink: Observer[A]): Cancelable = source.value.unsafeSubscribe(sink)
    }
  }

  @inline implicit class ConnectableObservableMaybeValueOperations[A](val source: Connectable[Observable.MaybeValue[A]]) extends AnyVal {
    def refCount: Observable.MaybeValue[A] = new Observable.MaybeValue[A] {
      def now()                                          = source.value.now()
      def unsafeSubscribe(sink: Observer[A]): Cancelable = Cancelable.composite(source.value.unsafeSubscribe(sink), source.connect())
    }
    def hot: Observable.HotMaybeValue[A]   = new Observable.HotMaybeValue[A] {
      val cancelable                                     = source.connect()
      def now()                                          = source.value.now()
      def unsafeSubscribe(sink: Observer[A]): Cancelable = source.value.unsafeSubscribe(sink)
    }
  }
}
