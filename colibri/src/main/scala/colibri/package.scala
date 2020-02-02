package object colibri {
  type Subject[-I,+O] = Observer[I] with Observable[O]

  val handler = HandlerEnvironment[Observer, Observable, Lambda[X => Subject[X,X]], Subject](Observer.liftSink, Observable.liftSource, Subject.createHandler, Subject.createProHandler)
}
