package colibri.jsdom

import org.scalajs.dom
import org.scalajs.dom.StorageEvent
import org.scalajs.dom.window.{localStorage, sessionStorage}

import colibri._

class Storage(storage: dom.Storage) {
  private def storageEventsForKey(key: String): Observable[Option[String]] =
    // StorageEvents are only fired if the localStorage was changed in another window
    EventObservable[StorageEvent](dom.window, "storage").collect {
      case e: StorageEvent if e.storageArea == storage && e.key == key  =>
        // newValue is either String or null if removed or cleared
        // Option() transformes this to Some(string) or None
        Option(e.newValue)
      case e: StorageEvent if e.storageArea == storage && e.key == null =>
        // storage.clear() emits an event with key == null
        None
    }

  private def storageWriter(key: String): Option[String] => Unit = {
    case Some(data) => storage.setItem(key, data)
    case None       => storage.removeItem(key)
  }

  private def storageSubject(key: String, withEvents: Boolean): Subject[Option[String]] = {

    val eventListener = if (withEvents) storageEventsForKey(key) else Observable.empty

    Subject
      .publish[Option[String]]()
      .transformSubject(observer => observer.tap(storageWriter(key)))(observable =>
        observable.merge(eventListener).prependEval(Option(storage.getItem(key))).distinct.replayLatest.refCount,
      )
  }

  def subject(key: String): Subject[Option[String]] = storageSubject(key, withEvents = false)

  def subjectWithEvents(key: String): Subject[Option[String]] = storageSubject(key, withEvents = true)
}

object Storage {
  object Local   extends Storage(localStorage)
  object Session extends Storage(sessionStorage)
}
