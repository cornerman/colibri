package colibri

object UnhandledErrorReporter {
  private[colibri] val errorSubject = Subject.publish[Throwable]
  @inline def error: Observable[Throwable] = errorSubject
}
