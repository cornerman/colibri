package colibri.jsdom

import colibri._

import scala.scalajs.js
import org.scalajs.dom

final class EventObservable[+EV <: dom.Event](observable: Observable[EV]) extends Observable[EV] {
  def subscribe(sink: Observer[EV]): Cancelable = observable.subscribe(sink)

  def filter(f: EV => Boolean): EventObservable[EV] = new EventObservable(observable.filter(f))

  private def withOperator(newOperator: EV => Unit): EventObservable[EV] = new EventObservable(observable.map { ev => newOperator(ev); ev })

  def preventDefault: EventObservable[EV]           = withOperator(_.preventDefault())
  def stopPropagation: EventObservable[EV]          = withOperator(_.stopPropagation())
  def stopImmediatePropagation: EventObservable[EV] = withOperator(_.stopImmediatePropagation())
}

object EventObservable {
  def apply[EV <: dom.Event](target: dom.EventTarget, eventType: String): Observable[EV] = new EventObservable(new Observable[EV] {
    def subscribe(sink: Observer[EV]): Cancelable = {
      var isCancel = false

      val eventHandler: js.Function1[EV, Unit] = { v =>
        if (!isCancel) {
          sink.onNext(v)
        }
      }

      def register()   = target.addEventListener(eventType, eventHandler)
      def unregister() = if (!isCancel) {
        isCancel = true
        target.removeEventListener(eventType, eventHandler)
      }

      register()

      Cancelable(() => unregister())
    }
  })
}
