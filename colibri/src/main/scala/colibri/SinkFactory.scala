package colibri

//TODO: should have createsink typeclass to create without lift overhead
@inline final class SinkFactory[S[_] : LiftSink] {
  @inline def empty: S[Any] = Observer.empty.liftSink[S]
  @inline def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext(_)): S[A] = Observer.create(consume, failure).liftSink[S]
}
