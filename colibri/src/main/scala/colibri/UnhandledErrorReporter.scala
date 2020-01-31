package colibri

object UnhandledErrorReporter {
  private[colibri] val errorSubject = SinkSourceHandler.publish[Throwable]
  @inline def error: SourceStream[Throwable] = errorSubject
}
