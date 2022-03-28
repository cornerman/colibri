package colibri

import scala.scalajs.js
import colibri.helpers._

final class ReplaySubject[A] extends Observer[A] with Observable.MaybeValue[A] {

  private val state = new PublishSubject[A]

  private var current: Option[A] = None

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): Option[A] = current

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

final class ReplayAllSubject[A] extends Observer[A] {

  private val state = new PublishSubject[A]

  private val current = collection.mutable.ArrayBuffer[A]()

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): Seq[A] = current.toSeq

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

final class BehaviorSubject[A](private var current: A) extends Observer[A] with Observable.Value[A] {

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
    subscribers.push(observer)
    Cancelable { () =>
      if (isRunning) subscribers = JSArrayHelper.removeElementCopied(subscribers)(observer)
      else JSArrayHelper.removeElement(subscribers)(observer)
    }
  }
}

object Subject {
  type Value[A]      = Observer[A] with Observable.Value[A]
  type MaybeValue[A] = Observer[A] with Observable.MaybeValue[A]

  @deprecated("Use replayLatest instead", "0.3.4")
  def replay[O](): ReplaySubject[O]       = replayLatest[O]()
  @deprecated("Use replayLatest instead", "0.4.0")
  def replayLast[O](): ReplaySubject[O]   = replayLatest[O]()
  def replayLatest[O](): ReplaySubject[O] = new ReplaySubject[O]
  def replayAll[O](): ReplayAllSubject[O] = new ReplayAllSubject[O]

  def behavior[O](seed: O): BehaviorSubject[O] = new BehaviorSubject[O](seed)

  def publish[O](): PublishSubject[O] = new PublishSubject[O]

  def from[A](sink: Observer[A], source: Observable[A]): Subject[A] = ProSubject.from(sink, source)

  def create[A](sinkF: A => Unit, sourceF: Observer[A] => Cancelable): Subject[A] = ProSubject.create(sinkF, sourceF)
}

object ProSubject {
  type Value[-I, +O]      = Observer[I] with Observable.Value[O]
  type MaybeValue[-I, +O] = Observer[I] with Observable.MaybeValue[O]

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
