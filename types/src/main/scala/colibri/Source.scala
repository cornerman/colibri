package colibri
import cats.effect.{CancelToken, Sync}

trait Source[-F[_]] {
  def subscribe[E[_] : Sync, A](source: F[A]): E[CancelToken[E]]
}
object Source {
  @inline def apply[F[_]](implicit source: Source[F]): Source[F] = source
}
