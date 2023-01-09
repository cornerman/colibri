package colibri.reactive

trait RxPlatform {
  def apply[R](f: LiveOwner ?=> R): Rx[R] = Rx.function(implicit owner => f)
}
