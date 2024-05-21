package colibri

import scala.scalajs.js
import colibri.helpers._

final class ReplayLatestSubject[A] extends Observer[A] with Observable[A] {

  private val state = new PublishSubject[A]

  private var current: Option[A] = None

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): Option[A] = current

  def unsafeResetState(): Unit = {
    current = None
  }

  def unsafeOnNext(value: A): Unit = {
    current = Some(value)
    state.unsafeOnNext(value)
  }

  def unsafeOnError(error: Throwable): Unit = state.unsafeOnError(error)

  def unsafeSubscribe(sink: Observer[A]): Cancelable = {
    val cancelable = state.unsafeSubscribe(sink)
    current.foreach(sink.unsafeOnNext)
    cancelable
  }
}

final class ReplayAllSubject[A] extends Observer[A] with Observable[A] {

  private val state = new PublishSubject[A]

  private val current = collection.mutable.ArrayBuffer[A]()

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): Seq[A] = current.toSeq

  def unsafeResetState(): Unit = {
    current.clear()
  }

  def unsafeOnNext(value: A): Unit = {
    current += value
    state.unsafeOnNext(value)
  }

  def unsafeOnError(error: Throwable): Unit = state.unsafeOnError(error)

  def unsafeSubscribe(sink: Observer[A]): Cancelable = {
    val cancelable = state.unsafeSubscribe(sink)
    current.foreach(sink.unsafeOnNext)
    cancelable
  }
}

final class BehaviorSubject[A](private var current: A) extends Observer[A] with Observable[A] {

  private val state = new PublishSubject[A]

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): A = current

  def unsafeOnNext(value: A): Unit = {
    current = value
    state.unsafeOnNext(value)
  }

  def unsafeOnError(error: Throwable): Unit = state.unsafeOnError(error)

  def unsafeSubscribe(sink: Observer[A]): Cancelable = {
    val cancelable = state.unsafeSubscribe(sink)
    sink.unsafeOnNext(current)
    cancelable
  }
}

final class PublishSubject[A] extends Observer[A] with Observable[A] {

  private var subscribers = new js.Array[Observer[A]]
  private var isRunning   = false

  def hasSubscribers: Boolean = subscribers.nonEmpty

  def unsafeOnNext(value: A): Unit = {
    isRunning = true
    subscribers.foreach(_.unsafeOnNext(value))
    isRunning = false
  }

  def unsafeOnError(error: Throwable): Unit = {
    isRunning = true
    subscribers.foreach(_.unsafeOnError(error))
    isRunning = false
  }

  def unsafeSubscribe(sink: Observer[A]): Cancelable = {
    val observer = Observer.lift(sink)
    subscribers.push(observer): Unit
    Cancelable { () =>
      if (isRunning) subscribers = JSArrayHelper.removeElementCopied(subscribers)(observer)
      else JSArrayHelper.removeElement(subscribers)(observer)
    }
  }
}

object Subject {
  def replayLatest[O](): ReplayLatestSubject[O] = new ReplayLatestSubject[O]
  def replayAll[O](): ReplayAllSubject[O]       = new ReplayAllSubject[O]

  def behavior[O](seed: O): BehaviorSubject[O] = new BehaviorSubject[O](seed)

  def publish[O](): PublishSubject[O] = new PublishSubject[O]

  def from[A](sink: Observer[A], source: Observable[A]): Subject[A] = ProSubject.from(sink, source)

  def create[A](sinkF: A => Unit, sourceF: Observer[A] => Cancelable): Subject[A] = ProSubject.create(sinkF, sourceF)
}

object ProSubject {
  def from[I, O](sink: Observer[I], source: Observable[O]): ProSubject[I, O] = new Observer[I] with Observable[O] {
    @inline def unsafeOnNext(value: I): Unit                   = sink.unsafeOnNext(value)
    @inline def unsafeOnError(error: Throwable): Unit          = sink.unsafeOnError(error)
    @inline def unsafeSubscribe(sink: Observer[O]): Cancelable = source.unsafeSubscribe(sink)
  }

  def create[I, O](sinkF: I => Unit, sourceF: Observer[O] => Cancelable): ProSubject[I, O] = {
    val sink   = Observer.create(sinkF)
    val source = Observable.create(sourceF)
    from(sink, source)
  }
}
