package colibri

import cats.effect.Sync

trait Sink[-G[_]] {
  def onNext[F[_] : Sync, A](sink: G[A])(value: A): F[Unit]
  def onError[F[_] : Sync, A](sink: G[A])(error: Throwable): F[Unit]
}
object Sink {
  @inline def apply[G[_]](implicit sink: Sink[G]): Sink[G] = sink
}

trait LiftSink[+G[_]] {
  def lift[GG[_] : Sink, A](sink: GG[A]): G[A]
}
object LiftSink {
  @inline def apply[G[_]](implicit sink: LiftSink[G]): LiftSink[G] = sink
}

