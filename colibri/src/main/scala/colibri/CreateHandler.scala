package colibri

trait CreateHandler[+F[_]] {
  def publish[A]: F[A]
  def behavior[A]: F[A]
  def behavior[A](seed: A): F[A]
}
object CreateHandler {
  @inline def apply[F[_]](implicit handler: CreateHandler[F]): CreateHandler[F] = handler
}

trait CreateProHandler[+F[_,_]] {
  def publish[I,O](f: I => O): F[I,O]
  def behavior[I,O](f: I => O): F[I,O]
  def behavior[I,O](seed: I)(f: I => O): F[I,O]
  def from[SI[_] : Sink, SO[_] : Source, I,O](sink: SI[I], source: SO[O]): F[I, O]
}
object CreateProHandler {
  @inline def apply[F[_,_]](implicit handler: CreateProHandler[F]): CreateProHandler[F] = handler
}
