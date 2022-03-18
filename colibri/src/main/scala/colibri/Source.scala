package colibri

trait Source[-H[_]] {
  def unsafeSubscribe[A](source: H[A])(sink: Observer[A]): Cancelable

  @deprecated("Use unsafeSubscribe instead", "0.2.7")
  @inline final def subscribe[A](source: H[A])(sink: Observer[A]): Cancelable = unsafeSubscribe(source)(sink)
}
object Source       {
  @inline def apply[H[_]](implicit source: Source[H]): Source[H] = source
}

trait LiftSource[+H[_]] {
  def lift[HH[_]: Source, A](source: HH[A]): H[A]
}
object LiftSource       {
  @inline def apply[H[_]](implicit source: LiftSource[H]): LiftSource[H] = source
}
