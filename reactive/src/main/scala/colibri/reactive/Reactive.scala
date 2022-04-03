package colibri.reactive

import colibri._
import colibri.effect._
import cats.effect.SyncIO

import scala.concurrent.Future

trait Rx[+A] {
  def observable: Observable[A]
  def now(): Option[A]

  final def map[B](f: A => B)(implicit owner: Owner): Rx[B]                           = transform(_.map(f))
  final def mapIterable[B](f: A => Iterable[B])(implicit owner: Owner): Rx[B]         = transform(_.mapIterable(f))
  final def mapEffect[F[_]: RunEffect, B](f: A => F[B])(implicit owner: Owner): Rx[B] = transform(_.mapEffect(f))
  final def mapFuture[B](f: A => Future[B])(implicit owner: Owner): Rx[B]             = transform(_.mapFuture(f))

  final def as[B](value: B)(implicit owner: Owner): Rx[B]                           = transform(_.as(value))
  final def asDelay[B](value: => B)(implicit owner: Owner): Rx[B]                   = transform(_.asDelay(value))
  final def asEffect[F[_]: RunEffect, B](value: F[B])(implicit owner: Owner): Rx[B] = transform(_.asEffect(value))
  final def asFuture[B](value: => Future[B])(implicit owner: Owner): Rx[B]          = transform(_.asFuture(value))

  final def collect[B](f: PartialFunction[A, B])(implicit owner: Owner): Rx[B] = transform(_.collect(f))
  final def filter(f: A => Boolean)(implicit owner: Owner): Rx[A]              = transform(_.filter(f))

  final def switchMap[B](f: A => Rx[B])(implicit owner: Owner): Rx[B] = transform(_.switchMap(f andThen (_.observable)))
  final def mergeMap[B](f: A => Rx[B])(implicit owner: Owner): Rx[B]  = transform(_.mergeMap(f andThen (_.observable)))

  final def transform[B](f: Observable[A] => Observable[B])(implicit owner: Owner): Rx[B] = Rx.observable(f(observable))

  final def subscribe()(implicit owner: Owner): Unit           = owner.own(() => observable.unsafeSubscribe())
  final def foreach(f: A => Unit)(implicit owner: Owner): Unit = owner.own(() => observable.unsafeForeach(f))
}

object Rx {
  def apply[R: SubscriptionOwner](f: Owner => R): SyncIO[R] = SyncIO {
    val owner  = Owner.unsafeHotRef()
    val result = f(owner)
    SubscriptionOwner[R].own(result)(owner.unsafeSubscribe)
  }

  def onSubscribe[R: SubscriptionOwner](f: Owner => R): R = {
    val owner  = Owner.ref()
    val result = f(owner)
    SubscriptionOwner[R].own(result)(owner.unsafeSubscribe)
  }

  def observable[A](observable: Observable[A])(implicit owner: Owner): Rx[A] = new RxObservable(observable)

  implicit object source extends Source[Rx] {
    @inline def unsafeSubscribe[A](source: Rx[A])(sink: Observer[A]): Cancelable = source.observable.unsafeSubscribe(sink)
  }

  implicit def liftSource(implicit owner: Owner): LiftSource[Rx] = new LiftSource[Rx] {
    @inline def lift[H[_]: Source, A](source: H[A]): Rx[A] = Rx.observable(Observable.lift[H, A](source))
  }
}

trait RxWriter[-A] {
  def observer: Observer[A]

  final def set(value: A): Unit = observer.unsafeOnNext(value)

  final def contramap[B](f: B => A): RxWriter[B]                   = transform(_.contramap(f))
  final def contramapIterable[B](f: B => Iterable[A]): RxWriter[B] = transform(_.contramapIterable(f))

  final def contracollect[B](f: PartialFunction[B, A]): RxWriter[B] = transform(_.contracollect(f))

  final def transform[B](f: Observer[A] => Observer[B]): RxWriter[B] = RxWriter.observer(f(observer))
}

object RxWriter {
  def observer[A](observer: Observer[A]): RxWriter[A] = new RxWriterObserver(observer)

  @inline implicit final class RxWriterOps[A](private val self: RxWriter[A]) extends AnyVal {
    def contrafilter(f: A => Boolean): RxWriter[A] = self.transform(_.contrafilter(f))
  }

  implicit object sink extends Sink[RxWriter] {
    @inline def unsafeOnNext[A](sink: RxWriter[A])(value: A): Unit          = sink.observer.unsafeOnNext(value)
    @inline def unsafeOnError[A](sink: RxWriter[A])(error: Throwable): Unit = sink.observer.unsafeOnError(error)
  }

  implicit object liftSink extends LiftSink[RxWriter] {
    @inline def lift[G[_]: Sink, A](sink: G[A]): RxWriter[A] = RxWriter.observer(Observer.lift(sink))
  }
}

trait Var[A] extends Rx[A] with RxWriter[A]

object Var {
  def apply[A](seed: A): Var[A] = new BehaviorVar(seed)
  def apply[A](): Var[A]        = new ReplayLatestVar[A]()
}

private final class RxObservable[A](inner: Observable[A])(implicit owner: Owner) extends Rx[A] {
  private val state = new ReplayLatestSubject[A]()

  val observable: Observable[A] = {
    val o = inner.distinctOnEquals.multicast(state).refCount
    owner.own(() => o.unsafeSubscribe())
    o
  }

  def now(): Option[A] = state.now()
}

private final class RxWriterObserver[A](val observer: Observer[A]) extends RxWriter[A]

private final class ReplayLatestVar[A] extends Var[A] {
  private val state = new ReplayLatestSubject[A]()

  val observable: Observable[A] = state.distinctOnEquals
  val observer: Observer[A]     = state

  def now(): Option[A] = state.now()
}

private final class BehaviorVar[A](seed: A) extends Var[A] {
  private val state = new BehaviorSubject[A](seed)

  val observable: Observable[A] = state.distinctOnEquals
  val observer: Observer[A]     = state

  def now(): Some[A] = Some(state.now())
}
