package object atta {
  val handler = HandlerEnvironment[SinkObserver, SourceStream, SinkSourceHandler.Simple, SinkSourceHandler](SinkObserver.liftSink, SourceStream.liftSource, SinkSourceHandler.createHandler, SinkSourceHandler.createProHandler)
}
