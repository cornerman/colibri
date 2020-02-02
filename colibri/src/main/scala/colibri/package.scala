package object colibri {
  type Subject[-I,+O] = Observer[I] with Observable[O]

  val handler = HandlerEnvironment[Observer, Observable, Subject.Uniform, Subject](Observer.liftSink, Observable.liftSource, Subject.createHandler, Subject.createProHandler)
}
