package colibri.jsdom

import colibri._
import org.scalajs.dom

object EventSourceObservable {
  def apply(url: String): Observable[dom.MessageEvent] =
    from(() => new dom.EventSource(url))

  def apply(url: String, eventSourceInit: dom.EventSourceInit): Observable[dom.MessageEvent] =
    from(() => new dom.EventSource(url, eventSourceInit))

  def from(createSource: () => dom.EventSource): Observable[dom.MessageEvent] = Observable.create { observer =>
    val source = createSource()
    source.onerror = { ev =>
      observer.unsafeOnError(new Exception(s"Failed EventSource (${ev.filename}:${ev.lineno}:${ev.colno}): ${ev.message}"))
    }
    source.onmessage = observer.unsafeOnNext

    Cancelable(source.close)
  }
}
