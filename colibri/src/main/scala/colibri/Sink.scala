package colibri

trait Sink[-G[_]] {
  def onNext[A](sink: G[A])(value: A): Unit
  def onError[A](sink: G[A])(error: Throwable): Unit
}
object Sink       {
  @inline def apply[G[_]](implicit sink: Sink[G]): Sink[G] = sink
}

trait LiftSink[+G[_]] {
  def lift[GG[_]: Sink, A](sink: GG[A]): G[A]
}
object LiftSink       {
  @inline def apply[G[_]](implicit sink: LiftSink[G]): LiftSink[G] = sink
}
