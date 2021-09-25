package colibri

trait Source[-F[_]] {
  def subscribe[G[_] : Sink, A](source: F[A])(sink: G[_ >: A]): Cancelable
}
object Source {
  @inline def apply[F[_]](implicit source: Source[F]): Source[F] = source
}

trait LiftSource[+F[_]] {
  def lift[H[_] : Source, A](source: H[A]): F[A]
}
object LiftSource {
  @inline def apply[F[_]](implicit source: LiftSource[F]): LiftSource[F] = source
}
