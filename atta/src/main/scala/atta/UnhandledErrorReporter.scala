package atta

object UnhandledErrorReporter {
  private[atta] val errorSubject = SinkSourceHandler.publish[Throwable]
  @inline def error: SourceStream[Throwable] = errorSubject
}
