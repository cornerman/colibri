package colibri.helpers

import colibri._

object UnhandledErrorReporter {
  private[colibri] val errorSubject = Subject.publish[Throwable]
  @inline def error: Observable[Throwable] = errorSubject
}
