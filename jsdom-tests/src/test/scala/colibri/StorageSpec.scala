package colibri

import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

import org.scalajs.dom
import org.scalajs.dom.{EventInit, Event}
import org.scalajs.dom.window
import scala.scalajs.js
import scala.collection.mutable

import colibri.jsdom.Storage
import cats.effect.{IO, unsafe}

trait LocalStorageMock {
  def dispatchStorageEvent(key: String, newValue: String, oldValue: String): Unit = {
    if (key == null) window.localStorage.clear()
    else {
      if (newValue == null) window.localStorage.removeItem(key)
      else window.localStorage.setItem(key, newValue)
    }

    val event = new Event(
      "storage",
      new EventInit {
        bubbles = true
        cancelable = false
      },
    )
    event.asInstanceOf[js.Dynamic].key = key
    event.asInstanceOf[js.Dynamic].newValue = newValue
    event.asInstanceOf[js.Dynamic].oldValue = oldValue
    event.asInstanceOf[js.Dynamic].storageArea = window.localStorage
    dom.window.dispatchEvent(event)
    ()
  }
}

class StorageSpec extends AsyncFlatSpec with Matchers with LocalStorageMock {

  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime(
    compute = executionContext,
    blocking = executionContext,
    config = unsafe.IORuntimeConfig(),
    scheduler = unsafe.IORuntime.defaultScheduler,
    shutdown = () => (),
  )

  "LocalStorage" should "simple subject with events" in {
    var option: Option[Option[String]] = None

    val handler = Storage.Local.subjectWithEvents("hans")

    handler.unsafeForeach { o => option = Some(o) }

    option shouldBe Some(None)

    handler.unsafeOnNext(Some("gisela"))
    option shouldBe Some(Some("gisela"))

    handler.unsafeOnNext(None)
    option shouldBe Some(None)
  }

  it should "simple subject" in {
    var option: Option[Option[String]] = None

    val handler = Storage.Local.subject("hans")

    handler.unsafeForeach { o => option = Some(o) }

    option shouldBe Some(None)

    handler.unsafeOnNext(Some("gisela"))
    option shouldBe Some(Some("gisela"))

    handler.unsafeOnNext(None)
    option shouldBe Some(None)
  }

  it should "subject with events scenario" in {

    val key                    = "banana"
    val triggeredHandlerEvents = mutable.ArrayBuffer.empty[Option[String]]

    assert(window.localStorage.getItem(key) == null)

    val test = IO(Storage.Local.subjectWithEvents(key)).flatMap { storageHandler =>
      storageHandler.unsafeForeach { e => triggeredHandlerEvents += e }
      assert(window.localStorage.getItem(key) == null)
      assert(triggeredHandlerEvents.toList == List(None))

      storageHandler.unsafeOnNext(Some("joe"))
      assert(window.localStorage.getItem(key) == "joe")
      assert(triggeredHandlerEvents.toList == List(None, Some("joe")))

      var initialValue: Option[String] = null

      IO(Storage.Local.subjectWithEvents(key)).map { sh =>
        sh.unsafeForeach { initialValue = _ }
        assert(initialValue == Some("joe"))

        storageHandler.unsafeOnNext(None)
        assert(window.localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None))

        // window.localStorage.setItem(key, "split") from another window
        dispatchStorageEvent(key, newValue = "split", null)
        assert(window.localStorage.getItem(key) == "split")
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split")))

        // window.localStorage.removeItem(key) from another window
        dispatchStorageEvent(key, null, "split")
        assert(window.localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None))

        // only trigger handler if value changed
        storageHandler.unsafeOnNext(None)
        assert(window.localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None))

        storageHandler.unsafeOnNext(Some("rhabarbar"))
        assert(window.localStorage.getItem(key) == "rhabarbar")
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None, Some("rhabarbar")))

        // window.localStorage.clear() from another window
        dispatchStorageEvent(null, null, null)
        assert(window.localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None, Some("rhabarbar"), None))
      }
    }

    test.unsafeToFuture()
  }

}
