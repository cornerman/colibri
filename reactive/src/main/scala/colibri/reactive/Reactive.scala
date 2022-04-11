package colibri.reactive

import colibri._
import colibri.effect._

import scala.concurrent.Future

trait Rx[+A] {
  def observable: Observable[A]
  def now(): A

  final def apply()(implicit liveOwner: LiveOwner): A = liveOwner.unsafeLive(this)

  final def map[B](f: A => B)(implicit owner: Owner): Rx[B]                          = transformSync(_.map(f))
  final def mapEither[B](f: A => Either[Throwable, B])(implicit owner: Owner): Rx[B] = transformSync(_.mapEither(f))
  final def tap(f: A => Unit)(implicit owner: Owner): Rx[A]                          = transformSync(_.tap(f))

  final def collect[B](f: PartialFunction[A, B])(seed: => B)(implicit owner: Owner): Rx[B] = transform(_.collect(f))(seed)

  final def mapSyncEffect[F[_]: RunSyncEffect, B](f: A => F[B])(implicit owner: Owner): Rx[B]     = transformSync(_.mapEffect(f))
  final def mapEffect[F[_]: RunEffect, B](f: A => F[B])(seed: => B)(implicit owner: Owner): Rx[B] = transform(_.mapEffect(f))(seed)
  final def mapFuture[B](f: A => Future[B])(seed: => B)(implicit owner: Owner): Rx[B]             = transform(_.mapFuture(f))(seed)

  final def as[B](value: B)(implicit owner: Owner): Rx[B]        = transformSync(_.as(value))
  final def asEval[B](value: => B)(implicit owner: Owner): Rx[B] = transformSync(_.asEval(value))

  final def asSyncEffect[F[_]: RunSyncEffect, B](value: F[B])(implicit owner: Owner): Rx[B]     = transformSync(_.asEffect(value))
  final def asEffect[F[_]: RunEffect, B](value: F[B])(seed: => B)(implicit owner: Owner): Rx[B] = transform(_.asEffect(value))(seed)
  final def asFuture[B](value: => Future[B])(seed: => B)(implicit owner: Owner): Rx[B]          = transform(_.asFuture(value))(seed)

  final def switchMap[B](f: A => Rx[B])(implicit owner: Owner): Rx[B] = transformSync(_.switchMap(f andThen (_.observable)))
  final def mergeMap[B](f: A => Rx[B])(implicit owner: Owner): Rx[B]  = transformSync(_.mergeMap(f andThen (_.observable)))

  final def subscribe()(implicit owner: Owner): Unit           = owner.own(() => observable.unsafeSubscribe())
  final def foreach(f: A => Unit)(implicit owner: Owner): Unit = owner.own(() => observable.unsafeForeach(f))

  final def transform[B](f: Observable[A] => Observable[B])(seed: => B)(implicit owner: Owner): Rx[B] = Rx.observable(f(observable))(seed)
  final def transformSync[B](f: Observable[A] => Observable[B])(implicit owner: Owner): Rx[B]         = Rx.observableSync(f(observable))
}

object Rx extends RxPlatform {
  def function[R](f: LiveOwner => R)(implicit owner: Owner): Rx[R] = {
    val subject = Subject.behavior[Any](())

    val observable = subject.switchMap { _ =>
      val liveOwner = LiveOwner.unsafeHotRef()
      val result    = f(liveOwner)
      Observable[R](result)
        .subscribing(liveOwner.liveObservable.dropSyncAll.head.to(subject))
        .tapCancel(liveOwner.cancelable.unsafeCancel)
    }

    Rx.observableSync(observable)
  }

  def const[A](value: A): Rx[A] = new RxConst(value)

  def observable[A](observable: Observable[A])(seed: => A)(implicit owner: Owner): Rx[A] = new RxObservable(observable, seed)

  def observableSync[A](observable: Observable[A])(implicit owner: Owner): Rx[A] = new RxObservableSync(observable)

  @inline implicit final class RxOps[A](private val self: Rx[A]) extends AnyVal {
    def scan(f: (A, A) => A)(implicit owner: Owner): Rx[A] = scan(self.now())(f)

    def scan[B](seed: B)(f: (B, A) => B)(implicit owner: Owner): Rx[B] = self.transformSync(_.scan0(seed)(f))

    def filter(f: A => Boolean)(seed: => A)(implicit owner: Owner): Rx[A] = self.transform(_.filter(f))(seed)
  }

  implicit object source extends Source[Rx] {
    def unsafeSubscribe[A](source: Rx[A])(sink: Observer[A]): Cancelable = source.observable.unsafeSubscribe(sink)
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
    def lift[G[_]: Sink, A](sink: G[A]): RxWriter[A] = RxWriter.observer(Observer.lift(sink))
  }
}

trait Var[A] extends Rx[A] with RxWriter[A] {
  final def lens[B](read: A => B)(write: (A, B) => A)(implicit owner: Owner): Var[B] = new VarLens(this, read, write)
}

object Var {
  def apply[A](seed: A): Var[A] = new VarSubject(seed)
}

private final class RxConst[A](value: A) extends Rx[A] {
  val observable: Observable[A] = Observable.pure(value)
  def now(): A                  = value
}

private final class RxObservable[A](inner: Observable[A], seed: => A)(implicit owner: Owner) extends Rx[A] {
  private val state = new ReplayLatestSubject[A]()

  val observable: Observable[A] = inner.dropSyncAll.prependEval(now()).distinctOnEquals.multicast(state).refCount
  owner.own(() => observable.unsafeSubscribe())

  def now(): A = state.now().getOrElse(seed)
}

private final class RxObservableSync[A](inner: Observable[A])(implicit owner: Owner) extends Rx[A] {
  private val state = new ReplayLatestSubject[A]()

  val observable: Observable[A] = inner.dropUntilSyncLatest.distinctOnEquals.multicast(state).refCount
  owner.own(() => observable.unsafeSubscribe())

  def now(): A = state.now().get
}

private final class RxWriterObserver[A](val observer: Observer[A]) extends RxWriter[A]

private final class VarSubject[A](seed: A) extends Var[A] {
  private val state = new BehaviorSubject[A](seed)

  val observable: Observable[A] = state.distinctOnEquals
  val observer: Observer[A]     = state

  def now(): A = state.now()
}

private final class VarLens[A, B](inner: Var[A], read: A => B, write: (A, B) => A)(implicit owner: Owner) extends Var[B] {
  private val innerRead = inner.map(read)

  def now()      = innerRead.now()
  val observable = innerRead.observable
  val observer   = inner.observer.contramap(write(inner.now(), _))
}
