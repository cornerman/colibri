package colibri.reactive

import cats.Monoid
import cats.effect.SyncIO
import colibri._
import colibri.effect._
import monocle.{Iso, Lens, Prism}

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object RxMissingNowException
    extends Exception(
      "Missing current value inside an RxState (Rx or RxLater). Make sure, the RxState has active subscriptions when calling nowIfSubscribed.",
    )

trait RxSourceSelf[+Self[+X] <: RxSource[X], +SelfSync[+X] <: RxSource[X], +A] {
  def observable: Observable[A]

  def selfRxSync: SelfSync[A]
  def transformRx[B](f: Observable[A] => Observable[B]): Self[B]
  def transformRxSync[B](f: Observable[A] => Observable[B]): SelfSync[B]

  final def subscribe: SyncIO[Cancelable] = observable.subscribeSyncIO

  final def unsafeSubscribe(): Cancelable                    = observable.unsafeSubscribe()
  final def unsafeSubscribe(writer: RxWriter[A]): Cancelable = observable.unsafeSubscribe(writer.observer)
  final def unsafeForeach(f: A => Unit): Cancelable          = observable.unsafeForeach(f)

  final def hot: SyncIO[SelfSync[A]] = SyncIO(unsafeHot())
  final def unsafeHot(): SelfSync[A] = {
    val _ = unsafeSubscribe()
    selfRxSync
  }

  final def map[B](f: A => B): SelfSync[B] = transformRxSync(_.map(f))
  final def tap(f: A => Unit): SelfSync[A] = transformRxSync(_.tap(f))

  final def collect[B](f: PartialFunction[A, B]): Self[B]       = transformRx(_.collect(f))
  final def mapEither[B](f: A => Either[Throwable, B]): Self[B] = transformRx(_.mapEither(f))
  final def drop(n: Int): Self[A]                               = transformRx(_.drop(n))

  final def mapSyncEffect[F[_]: RunSyncEffect, B](f: A => F[B]): SelfSync[B] = transformRxSync(_.mapEffect(f))
  final def mapEffect[F[_]: RunEffect, B](f: A => F[B]): Self[B]             = transformRx(_.mapEffect(f))
  final def mapFuture[B](f: A => Future[B]): Self[B]                         = transformRx(_.mapFuture(f))

  final def as[B](value: B): SelfSync[B]        = transformRxSync(_.as(value))
  final def asEval[B](value: => B): SelfSync[B] = transformRxSync(_.asEval(value))

  final def asSyncEffect[F[_]: RunSyncEffect, B](value: F[B]): SelfSync[B] = transformRxSync(_.asEffect(value))
  final def asEffect[F[_]: RunEffect, B](value: F[B]): Self[B]             = transformRx(_.asEffect(value))
  final def asFuture[B](value: => Future[B]): Self[B]                      = transformRx(_.asFuture(value))

  final def via(writer: RxWriter[A]): SelfSync[A] = transformRxSync(_.via(writer.observer))

  final def switchMap[B](f: A => RxSource[B]): Self[B] = transformRx(_.switchMap(f andThen (_.observable)))
  final def mergeMap[B](f: A => RxSource[B]): Self[B]  = transformRx(_.mergeMap(f andThen (_.observable)))
  final def concatMap[B](f: A => RxSource[B]): Self[B] = transformRx(_.concatMap(f andThen (_.observable)))

  final def combineLatestMap[B, R](sourceB: RxSource[B])(f: (A, B) => R): Self[R] =
    transformRx(_.combineLatestMap(sourceB.observable)(f))
  final def combineLatest[B](sourceB: RxSource[B]): Self[(A, B)]                  =
    transformRx(_.combineLatest(sourceB.observable))

  final def withLatestMap[B, R](sourceB: RxSource[B])(f: (A, B) => R): Self[R] =
    transformRx(_.withLatestMap(sourceB.observable)(f))
  final def withLatest[B](sourceB: RxSource[B]): Self[(A, B)]                  =
    transformRx(_.withLatest(sourceB.observable))
}

object RxSourceSelf {
  implicit object source extends Source[RxSource] {
    def unsafeSubscribe[A](source: RxSource[A])(sink: Observer[A]): Cancelable = source.observable.unsafeSubscribe(sink)
  }

  @inline implicit final class RxSourceOps[Self[+X] <: RxSource[X], SelfSync[+X] <: RxSource[X], A](val self: RxSourceSelf[Self, SelfSync, A]) extends AnyVal {
    def scan[B](seed: => B)(f: (B, A) => B): SelfSync[B] = self.transformRxSync(_.scan(seed)(f))

    def filter(f: A => Boolean): Self[A] = self.transformRx(_.filter(f))
  }

  @inline implicit final class RxSourceBooleanOps[Self[+X] <: RxSource[X], SelfSync[+X] <: RxSource[X]](private val self: RxSourceSelf[Self, SelfSync, Boolean]) extends AnyVal {
    @inline def toggle[A](ifTrue: => A, ifFalse: => A): SelfSync[A] = self.map {
      case true  => ifTrue
      case false => ifFalse
    }

    @inline def toggle[A: Monoid](ifTrue: => A): SelfSync[A] = toggle(ifTrue, Monoid[A].empty)

    @inline def negated: SelfSync[Boolean] = self.map(x => !x)
  }
}

trait RxSource[+A] extends RxSourceSelf[RxSource, RxSource, A]

trait RxEvent[+A] extends RxSource[A] with RxSourceSelf[RxEvent, RxEvent, A] {
  final override def selfRxSync: RxEvent[A]                                            = this
  final override def transformRx[B](f: Observable[A] => Observable[B]): RxEvent[B]     = RxEvent.observable(f(observable))
  final override def transformRxSync[B](f: Observable[A] => Observable[B]): RxEvent[B] = RxEvent.observable(f(observable))
}

object RxEvent extends RxPlatform {
  private val _empty: RxEvent[Nothing] = observableUnshared(Observable.empty)
  def empty[A]: RxEvent[A]             = _empty

  def apply[A](values: A*): RxEvent[A] = iterable(values)

  def iterable[A](values: Iterable[A]): RxEvent[A] = observableUnshared(Observable.fromIterable(values))

  def future[A](future: => Future[A]): RxEvent[A]          = observable(Observable.fromFuture(future))
  def effect[F[_]: RunEffect, A](effect: F[A]): RxEvent[A] = observable(Observable.fromEffect(effect))

  def merge[A](rxs: RxEvent[A]*): RxEvent[A]  = observableUnshared(Observable.mergeIterable(rxs.map(_.observable)))
  def switch[A](rxs: RxEvent[A]*): RxEvent[A] = observableUnshared(Observable.switchIterable(rxs.map(_.observable)))
  def concat[A](rxs: RxEvent[A]*): RxEvent[A] = observableUnshared(Observable.concatIterable(rxs.map(_.observable)))

  def observable[A](observable: Observable[A]): RxEvent[A]                 = observableUnshared(observable.publish.refCount)
  private def observableUnshared[A](observable: Observable[A]): RxEvent[A] = new RxEventObservable(observable)
}

trait RxState[+A] extends RxSource[A] with RxSourceSelf[RxLater, RxState, A] {
  final override def transformRx[B](f: Observable[A] => Observable[B]): RxLater[B] = RxLater.observable(f(observable))
}

trait RxLater[+A] extends RxState[A] with RxSourceSelf[RxLater, RxLater, A] {
  type SelfSync[+X] = RxLater[X]

  final override def selfRxSync: RxLater[A]                                            = this
  final override def transformRxSync[B](f: Observable[A] => Observable[B]): RxLater[B] = RxLater.observable(f(observable))
}

object RxLater {
  def empty[A]: RxLater[A] = RxLaterEmpty

  def future[A](future: => Future[A]): RxLater[A]          = observable(Observable.fromFuture(future))
  def effect[F[_]: RunEffect, A](effect: F[A]): RxLater[A] = observable(Observable.fromEffect(effect))

  def observable[A](observable: Observable[A]): RxLater[A] = new RxLaterObservable(observable)

  @inline implicit final class RxLaterOps[A](private val self: RxLater[A]) extends AnyVal {
    def toRx: Rx[Option[A]]     = Rx.observableSeed(self.observable.map[Option[A]](Some.apply))(None)
    def toRx(seed: => A): Rx[A] = Rx.observableSeed(self.observable)(seed)
  }
}

trait Rx[+A] extends RxState[A] with RxSourceSelf[RxLater, Rx, A] {
  type SelfSync[+X] = Rx[X]

  def apply()(implicit owner: LiveOwner): A
  def now()(implicit owner: NowOwner): A
  def nowIfSubscribedOption(): Option[A]

  final def nowIfSubscribed(): A = nowIfSubscribedOption().getOrElse(throw RxMissingNowException)

  final override def selfRxSync: Rx[A]                                            = this
  final override def transformRxSync[B](f: Observable[A] => Observable[B]): Rx[B] = Rx.observableSync(f(observable))
}

object Rx extends RxPlatform {
  def function[R](f: LiveOwner => R): Rx[R] = {
    val subject = Subject.behavior[Any](())

    val observable = subject.switchMap { _ =>
      val owner = LiveOwner.unsafeHotRef()
      try {
        val result = f(owner)
        Observable[R](result)
          .subscribing(owner.liveObservable.dropSyncAll.head.via(subject))
          .tapCancel(owner.cancelable.unsafeCancel)
      } catch {
        case NonFatal(t) =>
          owner.cancelable.unsafeCancel()
          Observable.raiseError(t)
        case t: Throwable =>
          owner.cancelable.unsafeCancel()
          throw t
      }
    }

    Rx.observableSync(observable)
  }

  def const[A](value: A): Rx[A] = new RxConst(value)

  def observableSeed[A](observable: Observable[A])(seed: => A): Rx[A] = observableSync(observable.prependEval(seed))

  def observableSync[A](observable: Observable[A]): Rx[A] = new RxSyncObservable(observable)

  @inline implicit final class RxLaterOps[A](private val self: Rx[A]) extends AnyVal {
    def toRxLater: RxLater[A] = new RxLaterWrap(self)
  }
}

trait RxWriter[-A] {
  def observer: Observer[A]

  final def set(value: A): Unit = observer.unsafeOnNext(value)

  final def as(value: A): RxWriter[Any]                            = contramap(_ => value)
  final def contramap[B](f: B => A): RxWriter[B]                   = transformRxWriter(_.contramap(f))
  final def contramapIterable[B](f: B => Iterable[A]): RxWriter[B] = transformRxWriter(_.contramapIterable(f))

  final def contracollect[B](f: PartialFunction[B, A]): RxWriter[B] = transformRxWriter(_.contracollect(f))

  final def transformRxWriter[B](f: Observer[A] => Observer[B]): RxWriter[B] = RxWriter.observer(f(observer))
}

object RxWriter {
  def observer[A](observer: Observer[A]): RxWriter[A] = new RxWriterObserver(observer)

  @inline implicit final class RxWriterOps[A](private val self: RxWriter[A]) extends AnyVal {
    def contrafilter(f: A => Boolean): RxWriter[A] = self.transformRxWriter(_.contrafilter(f))
    def tap(f: A => Unit): RxWriter[A]             = self.transformRxWriter(_.tap(f))
  }

  implicit object sink extends Sink[RxWriter] {
    @inline def unsafeOnNext[A](sink: RxWriter[A])(value: A): Unit          = sink.observer.unsafeOnNext(value)
    @inline def unsafeOnError[A](sink: RxWriter[A])(error: Throwable): Unit = sink.observer.unsafeOnError(error)
  }

  implicit object liftSink extends LiftSink[RxWriter] {
    def lift[G[_]: Sink, A](sink: G[A]): RxWriter[A] = RxWriter.observer(Observer.lift(sink))
  }
}

trait VarSource[A] extends RxWriter[A] with RxSource[A]

trait VarEvent[A] extends VarSource[A] with RxEvent[A] {
  final def transformVar[A2](f: RxWriter[A] => RxWriter[A2])(g: RxEvent[A] => RxEvent[A2]): VarEvent[A2] = VarEvent.create(f(this), g(this))
  final def transformVarRead(g: RxEvent[A] => RxEvent[A]): VarEvent[A]                                = VarEvent.create(this, g(this))
  final def transformVarWrite(f: RxWriter[A] => RxWriter[A]): VarEvent[A]                             = VarEvent.create(f(this), this)
}

object VarEvent {
  def apply[A](): VarEvent[A] = new VarEventSubject

  def subject[A](read: Subject[A]): VarEvent[A] = create(RxWriter.observer(read), RxEvent.observable(read))

  def create[A](write: RxWriter[A], read: RxEvent[A]): VarEvent[A] = new VarEventCreate(write, read)
}

trait VarState[A] extends RxWriter[A] with RxState[A]

trait VarLater[A] extends VarState[A] with RxLater[A] {
  final def transformVar[A2](f: RxWriter[A] => RxWriter[A2])(g: RxLater[A] => RxLater[A2]): VarLater[A2] =
    VarLater.createStateless(f(this), g(this))
  final def transformVarRead(g: RxLater[A] => RxLater[A]): VarLater[A]                                     = VarLater.createStateless(this, g(this))
  final def transformVarWrite(f: RxWriter[A] => RxWriter[A]): VarLater[A]                             = VarLater.createStateless(f(this), this)
}

object VarLater {
  def apply[A](): VarLater[A] = new VarLaterSubject

  def subject[A](read: Subject[A]): VarLater[A] = createStateless(RxWriter.observer(read), RxLater.observable(read))

  def createStateful[A](write: RxWriter[A], read: RxLater[A]): VarLater[A] = new VarLaterCreateStateful(write, read)
  def createStateless[A](write: RxWriter[A], read: RxLater[A]): VarLater[A] = new VarLaterCreateStateless(write, read)
}

trait Var[A] extends VarState[A] with Rx[A] {
  final def updateIfSubscribed(f: A => A): Unit = set(f(nowIfSubscribed()))
  final def update(f: A => A): Unit             = set(f(now()))

  final def transformVar[A2](f: RxWriter[A] => RxWriter[A2])(g: Rx[A] => Rx[A2]): Var[A2] = Var.createStateless(f(this), g(this))
  final def transformVarRead(g: Rx[A] => Rx[A]): Var[A]                                     = Var.createStateless(this, g(this))
  final def transformVarWrite(f: RxWriter[A] => RxWriter[A]): Var[A]                   = Var.createStateless(f(this), this)

  final def imap[A2](f: A2 => A)(g: A => A2): Var[A2]         = transformVar(_.contramap(f))(_.map(g))
  final def lens[B](read: A => B)(write: (A, B) => A): Var[B] =
    transformVar(_.contramap(write(nowIfSubscribed(), _)))(_.map(read))

  final def prism[A2](f: A2 => A)(g: A => Option[A2])(seed: => A2): Var[A2] =
    transformVar(_.contramap(f))(rx => Rx.observableSync(rx.observable.mapFilter(g).prependEval(seed)))

  final def subType[A2 <: A: ClassTag](seed: => A2): Var[A2] = prism[A2]((x: A2) => x) {
    case a: A2 => Some(a)
    case _     => None
  }(seed)

  final def imapO[B](optic: Iso[A, B]): Var[B]                = imap(optic.reverseGet(_))(optic.get(_))
  final def lensO[B](optic: Lens[A, B]): Var[B]               = lens(optic.get(_))((base, zoomed) => optic.replace(zoomed)(base))
  final def prismO[B](optic: Prism[A, B])(seed: => B): Var[B] =
    prism(optic.reverseGet(_))(optic.getOption(_))(seed)
}

object Var {
  def apply[A](seed: A): Var[A] = new VarSubject(seed)

  def subjectSync[A](read: Subject[A]): Var[A] = createStateless(RxWriter.observer(read), Rx.observableSync(read))

  def createStateful[A](write: RxWriter[A], read: Rx[A]): Var[A] = new VarCreateStateful(write, read)
  def createStateless[A](write: RxWriter[A], read: Rx[A]): Var[A] = new VarCreateStateless(write, read)

  @inline implicit class SeqVarOperations[A](rxvar: Var[Seq[A]]) {
    def sequence: Rx[Seq[Var[A]]] = Rx.observableSync(new Observable[Seq[Var[A]]] {

      def unsafeSubscribe(sink: Observer[Seq[Var[A]]]): Cancelable = {
        rxvar.observable.unsafeSubscribe(
          Observer.create(
            { seq =>
              sink.unsafeOnNext(seq.zipWithIndex.map { case (a, idx) =>
                val observer = new Observer[A] {
                  def unsafeOnNext(value: A): Unit = {
                    rxvar.set(seq.updated(idx, value))
                  }

                  def unsafeOnError(error: Throwable): Unit = {
                    sink.unsafeOnError(error)
                  }
                }
                Var.createStateless(RxWriter.observer(observer), Rx.const(a))
              })
            },
            sink.unsafeOnError,
          ),
        )
      }
    })
  }

  @inline implicit class OptionVarOperations[A](rxvar: Var[Option[A]]) {
    def sequence: Rx[Option[Var[A]]] = Rx.observableSync(new Observable[Option[Var[A]]] {

      def unsafeSubscribe(outerSink: Observer[Option[Var[A]]]): Cancelable = {
        var cache = Option.empty[Var[A]]

        val cancelable = Cancelable.variable()

        Cancelable.composite(
          rxvar.observable
            .unsafeSubscribe(
              Observer.create(
                {
                  case Some(next) =>
                    cache match {
                      case Some(prev) => prev.set(next)
                      case None       =>
                        val temp = Var(next)
                        cache = Some(temp)
                        cancelable.unsafeAdd(() => temp.observable.map(Some.apply).unsafeForeach(rxvar.set))
                        outerSink.unsafeOnNext(cache)
                    }

                  case None =>
                    cache = None
                    outerSink.unsafeOnNext(None)
                },
                outerSink.unsafeOnError,
              ),
            ),
          cancelable,
        )
      }
    })
  }
}

// RxEvent

private final class RxEventObservable[A](val observable: Observable[A]) extends RxEvent[A]

// Rx

private final class RxConst[A](value: A) extends Rx[A] {
  private lazy val someValue = Some(value)

  val observable: Observable[A] = Observable.pure(value)

  def apply()(implicit owner: LiveOwner): A = value
  def now()(implicit owner: NowOwner): A    = value
  def nowIfSubscribedOption(): Option[A]    = someValue
}

private final class RxSyncObservable[A](inner: Observable[A]) extends Rx[A] {
  private val state = Subject.replayLatest[A]()

  val observable: Observable[A] =
    inner.dropUntilSyncLatest.distinctOnEquals.tapCancel(state.unsafeResetState).multicast(state).refCount

  def apply()(implicit owner: LiveOwner) = owner.unsafeLive(this)
  def now()(implicit owner: NowOwner)       = owner.unsafeNow(this)
  def nowIfSubscribedOption()               = state.now()
}

// RxLater

private object RxLaterEmpty extends RxLater[Nothing] {
  def observable = Observable.empty
}

private final class RxLaterWrap[A](state: Rx[A]) extends RxLater[A] {
  def observable: Observable[A] = state.observable
}

private final class RxLaterObservable[A](inner: Observable[A]) extends RxLater[A] {
  private val state = Subject.replayLatest[A]()

  val observable: Observable[A] =
    inner.dropUntilSyncLatest.distinctOnEquals.tapCancel(state.unsafeResetState).multicast(state).refCount
}

// RxWriter

private final class RxWriterObserver[A](val observer: Observer[A]) extends RxWriter[A]

// VarEvent

private final class VarEventSubject[A] extends VarEvent[A] {
  private val state             = Subject.publish[A]()

  def observable: Observable[A] = state
  def observer: Observer[A]     = state
}

private final class VarEventCreate[A](innerWrite: RxWriter[A], innerRead: RxEvent[A]) extends VarEvent[A] {
  def observable = innerRead.observable
  def observer   = innerWrite.observer
}

private final class VarLaterSubject[A] extends VarLater[A] {
  private val state = Subject.replayLatest[A]()

  val observable: Observable[A] = state.distinctOnEquals
  def observer: Observer[A]     = state
}

private final class VarLaterCreateStateless[A](innerWrite: RxWriter[A], innerRead: RxLater[A]) extends VarLater[A] {
  def observable = innerRead.observable
  def observer   = innerWrite.observer
}

private final class VarLaterCreateStateful[A](innerWrite: RxWriter[A], innerRead: RxLater[A]) extends VarLater[A] {
  private val state = Subject.replayLatest[A]()

  val observable                            = innerRead.observable.subscribing(state.via(innerWrite.observer)).multicast(state).refCount
  def observer                              = state
}

// Var

private final class VarSubject[A](seed: A) extends Var[A] {
  private val state = Subject.behavior[A](seed)

  val observable: Observable[A] = state.distinctOnEquals
  def observer: Observer[A]     = state

  def apply()(implicit owner: LiveOwner) = owner.unsafeLive(this)
  def now()(implicit owner: NowOwner)       = state.now()
  def nowIfSubscribedOption()               = Some(state.now())
}

private final class VarCreateStateless[A](innerWrite: RxWriter[A], innerRead: Rx[A]) extends Var[A] {
  val observable = innerRead.observable
  val observer   = innerWrite.observer

  def apply()(implicit owner: LiveOwner) = innerRead()
  def now()(implicit owner: NowOwner)       = innerRead.now()
  def nowIfSubscribedOption()               = innerRead.nowIfSubscribedOption()
}

private final class VarCreateStateful[A](innerWrite: RxWriter[A], innerRead: Rx[A]) extends Var[A] {
  private val state = Subject.replayLatest[A]()

  val observable                            = innerRead.observable.subscribing(state.via(innerWrite.observer)).multicast(state).refCount
  def observer                              = state

  def apply()(implicit owner: LiveOwner) = owner.unsafeLive(this)
  def now()(implicit owner: NowOwner)       = owner.unsafeNow(this)
  def nowIfSubscribedOption()               = state.now()
}

