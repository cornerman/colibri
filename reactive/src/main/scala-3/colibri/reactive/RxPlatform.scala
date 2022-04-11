package colibri.reactive

trait RxPlatform {
  def apply[R](f: LiveOwner ?=> R)(implicit owner: Owner): Rx[R] = Rx.function(implicit owner => f)
}
