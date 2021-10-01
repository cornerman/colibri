package colibri

trait CreateSubject[+GH[_]] {
  def publish[A]: GH[A]
  def replay[A]: GH[A]
  def behavior[A](seed: A): GH[A]
}
object CreateSubject {
  @inline def apply[GH[_]](implicit handler: CreateSubject[GH]): CreateSubject[GH] = handler
}

trait CreateProSubject[+GH[_,_]] {
  def from[GI[_] : Sink, HO[_] : Source, I,O](sink: GI[I], source: HO[O]): GH[I, O]
}
object CreateProSubject {
  @inline def apply[GH[_,_]](implicit handler: CreateProSubject[GH]): CreateProSubject[GH] = handler
}
