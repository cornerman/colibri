package colibri

trait Source[-H[_]] {
  def subscribe[A](source: H[A])(sink: Observer[A]): Cancelable
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
