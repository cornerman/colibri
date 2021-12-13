package colibri.router

import colibri.jsdom.EventObservable
import colibri.Observer
import colibri.Subject
import org.scalajs.dom.HashChangeEvent
import org.scalajs.dom.window

object Router {
  val locationHash = Subject
    .from[String](
      Observer.create(window.location.hash = _),
      EventObservable[HashChangeEvent](window, "hashchange")
        .map(_ => window.location.hash)
        .prepend(window.location.hash),
    )
    .transformSubjectSource(_.distinctOnEquals.replay.hot) // TODO: transformSink distinctOnEquals

  val hashFragment = locationHash.imapSubject[String](s => s"#${s}")(_.tail)
  val path         = hashFragment.imapSubject[Path](_.pathString)(Path(_))
}
