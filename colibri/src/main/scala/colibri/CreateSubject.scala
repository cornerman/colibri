package colibri

trait CreateSubject[+H[_]] {
  def publish[A]: H[A]
  def replay[A]: H[A]
  def behavior[A](seed: A): H[A]
}
object CreateSubject {
  @inline def apply[H[_]](implicit handler: CreateSubject[H]): CreateSubject[H] = handler
}

trait CreateProSubject[+GH[_,_]] {
  def from[GI[_] : Sink, HO[_] : Source, I,O](sink: GI[I], source: HO[O]): GH[I, O]
}
object CreateProSubject {
  @inline def apply[GH[_,_]](implicit handler: CreateProSubject[GH]): CreateProSubject[GH] = handler
}
