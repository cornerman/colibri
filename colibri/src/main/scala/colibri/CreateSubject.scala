package colibri

trait CreateSubject[+F[_]] {
  def publish[A]: F[A]
  def replay[A]: F[A]
  def behavior[A](seed: A): F[A]
}
object CreateSubject {
  @inline def apply[F[_]](implicit handler: CreateSubject[F]): CreateSubject[F] = handler
}

trait CreateProSubject[+F[_,_]] {
  def from[SI[_] : Sink, SO[_] : Source, I,O](sink: SI[I], source: SO[O]): F[I, O]
}
object CreateProSubject {
  @inline def apply[F[_,_]](implicit handler: CreateProSubject[F]): CreateProSubject[F] = handler
}
