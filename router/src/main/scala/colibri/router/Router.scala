package colibri.router

import colibri.Cancelable
import colibri.Observable
import colibri.Observer
import colibri.Subject
import org.scalajs.dom.raw.HashChangeEvent
import org.scalajs.dom.window

import scala.scalajs.js

object Router {
  val locationHash = Subject
    .from[Observer, Observable, String](
      Observer.create(window.location.hash = _),
      Observable
        .create { (obs: Observer[String]) =>
          val handler: js.Function1[HashChangeEvent, Unit] = _ => {
            obs.onNext(window.location.hash)
          }
          window.addEventListener("hashchange", handler, false)
          Cancelable(() => window.removeEventListener("hashchange", handler, false))
        }
        .startWith(Seq(window.location.hash)),
    )
    .transformSubjectSource(_.distinctOnEquals.replay.hot) // TODO: transformSink distinctOnEquals

  val hashFragment = locationHash.imapSubject[String](s => s"#${s}")(_.tail)
  val path         = hashFragment.imapSubject[Path](_.pathString)(Path(_))
}
