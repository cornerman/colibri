package colibri

trait Source[-H[_]] {
  def subscribe[HH[_] : Sink, A](source: H[A])(sink: HH[_ >: A]): Cancelable
}
object Source {
  @inline def apply[H[_]](implicit source: Source[H]): Source[H] = source
}

trait LiftSource[+H[_]] {
  def lift[H[_] : Source, A](source: H[A]): H[A]
}
object LiftSource {
  @inline def apply[H[_]](implicit source: LiftSource[H]): LiftSource[H] = source
}
