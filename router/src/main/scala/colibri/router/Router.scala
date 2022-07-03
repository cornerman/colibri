package colibri.router

import colibri.jsdom.EventObservable
import colibri.Observer
import colibri.Subject
import colibri.reactive._
import colibri.reactive.Owner.unsafeImplicits.unsafeGlobalOwner
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
    .transformSubjectSource(_.distinctOnEquals.replayLatest.hot) // TODO: transformSink distinctOnEquals
  val hashFragment: Subject[String] = locationHash.imapSubject[String](s => s"#${s}")(_.tail)
  val path: Subject[Path]           = hashFragment.imapSubject[Path](_.pathString)(Path(_))

  lazy val locationHashVar              = Var.combine[String](
    Rx.observable(
      EventObservable[HashChangeEvent](window, "hashchange")
        .map(_ => window.location.hash),
    )(seed = window.location.hash),
    RxWriter.observer(Observer.create[String](window.location.hash = _)),
  )
  lazy val hashFragmentVar: Var[String] = locationHashVar.imap[String](s => s"#${s}")(_.tail)
  lazy val pathVar: Var[Path]           = hashFragmentVar.imap[Path](_.pathString)(Path(_))
}
