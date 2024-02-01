package colibri.jsdom

import colibri._
import org.scalajs.dom

object EventSourceObservable {
  case class Failed(event: dom.Event) extends Exception("Failed EventSource")

  def apply(url: String): Observable[dom.MessageEvent] =
    from(() => new dom.EventSource(url))

  def apply(url: String, eventSourceInit: dom.EventSourceInit): Observable[dom.MessageEvent] =
    from(() => new dom.EventSource(url, eventSourceInit))

  def from(createSource: () => dom.EventSource): Observable[dom.MessageEvent] = Observable.create { observer =>
    val source = createSource()
    source.onerror = { ev =>
      observer.unsafeOnError(Failed(ev))
    }
    source.onmessage = { ev =>
      observer.unsafeOnNext(ev)
    }

    Cancelable(source.close)
  }
}
