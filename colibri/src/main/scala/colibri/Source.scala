package colibri

trait Source[-G[_]] {
  def subscribe[GG[_] : Sink, A](source: G[A])(sink: GG[_ >: A]): Cancelable
}
object Source {
  @inline def apply[G[_]](implicit source: Source[G]): Source[G] = source
}

trait LiftSource[+G[_]] {
  def lift[H[_] : Source, A](source: H[A]): G[A]
}
object LiftSource {
  @inline def apply[G[_]](implicit source: LiftSource[G]): LiftSource[G] = source
}
