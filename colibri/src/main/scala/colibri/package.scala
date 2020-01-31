package object colibri {
  val handler = HandlerEnvironment[SinkObserver, SourceStream, SinkSourceHandler.Simple, SinkSourceHandler](SinkObserver.liftSink, SourceStream.liftSource, SinkSourceHandler.createHandler, SinkSourceHandler.createProHandler)
}
