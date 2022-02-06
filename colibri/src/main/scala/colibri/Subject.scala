package colibri

import scala.scalajs.js
import colibri.helpers._

final class ReplaySubject[A] extends Observer[A] with Observable.MaybeValue[A] {

  private val state = new PublishSubject[A]

  private var current: Option[A] = None

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): Option[A] = current

  def onNext(value: A): Unit = {
    current = Some(value)
    state.onNext(value)
  }

  def onError(error: Throwable): Unit = state.onError(error)

  def subscribe(sink: Observer[A]): Cancelable = {
    val cancelable = state.subscribe(sink)
    current.foreach(sink.onNext)
    cancelable
  }
}

final class BehaviorSubject[A](private var current: A) extends Observer[A] with Observable.Value[A] {

  private val state = new PublishSubject[A]

  def hasSubscribers: Boolean = state.hasSubscribers

  @inline def now(): A = current

  def onNext(value: A): Unit = {
    current = value
    state.onNext(value)
  }

  def onError(error: Throwable): Unit = state.onError(error)

  def subscribe(sink: Observer[A]): Cancelable = {
    val cancelable = state.subscribe(sink)
    sink.onNext(current)
    cancelable
  }
}

final class PublishSubject[A] extends Observer[A] with Observable[A] {

  private var subscribers = new js.Array[Observer[A]]
  private var isRunning   = false

  def hasSubscribers: Boolean = subscribers.nonEmpty

  def onNext(value: A): Unit = {
    isRunning = true
    subscribers.foreach(_.onNext(value))
    isRunning = false
  }

  def onError(error: Throwable): Unit = {
    isRunning = true
    subscribers.foreach(_.onError(error))
    isRunning = false
  }

  def subscribe(sink: Observer[A]): Cancelable = {
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

  def replay[O]: ReplaySubject[O] = new ReplaySubject[O]

  def behavior[O](seed: O): BehaviorSubject[O] = new BehaviorSubject[O](seed)

  def publish[O]: PublishSubject[O] = new PublishSubject[O]

  def from[A](sink: Observer[A], source: Observable[A]): Subject[A] = ProSubject.from(sink, source)

  def create[A](sinkF: A => Unit, sourceF: Observer[A] => Cancelable): Subject[A] = ProSubject.create(sinkF, sourceF)
}

object ProSubject {
  type Value[-I, +O]      = Observer[I] with Observable.Value[O]
  type MaybeValue[-I, +O] = Observer[I] with Observable.MaybeValue[O]

  def from[I, O](sink: Observer[I], source: Observable[O]): ProSubject[I, O] = new Observer[I] with Observable[O] {
    @inline def onNext(value: I): Unit                   = sink.onNext(value)
    @inline def onError(error: Throwable): Unit          = sink.onError(error)
    @inline def subscribe(sink: Observer[O]): Cancelable = source.subscribe(sink)
  }

  def create[I, O](sinkF: I => Unit, sourceF: Observer[O] => Cancelable): ProSubject[I, O] = {
    val sink   = Observer.create(sinkF)
    val source = Observable.create(sourceF)
    from(sink, source)
  }
}
