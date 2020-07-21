package colibri

import cats.effect.Async

trait Sink[-F[_]] {
  def onNext[E[_] : Async, A](sink: F[A])(value: A): E[Unit]
  def onError[E[_] : Async, A](sink: F[A])(error: Throwable): E[Unit]
}
object Sink {
  @inline def apply[F[_]](implicit sink: Sink[F]): Sink[F] = sink
}
