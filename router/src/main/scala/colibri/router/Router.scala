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
    .transformSubjectSource(_.distinctOnEquals.replayLast.hot) // TODO: transformSink distinctOnEquals

  val hashFragment: Subject[String] = locationHash.imapSubject[String](s => s"#${s}")(_.tail)
  val path: Subject[Path]           = hashFragment.imapSubject[Path](_.pathString)(Path(_))
}
