package object colibri {
  type ProSubject[-I,+O] = Observer[I] with Observable[O]
  type Subject[T] = ProSubject[T,T]
}
