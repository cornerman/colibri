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
    extends Exception("Missing current value inside an RxState (Rx or RxLater). Make sure, the RxState has active subscriptions when calling nowIfSubscribed.")

trait RxSource[+A] {
  type Self[+X] <: RxSource[X]
  type SelfSync[+X] <: RxSource[X]

  def observable: Observable[A]

  def selfRxSourceSync: SelfSync[A]
  def transformRxSource[B](f: Observable[A] => Observable[B]): Self[B]
  def transformRxSourceSync[B](f: Observable[A] => Observable[B]): SelfSync[B]

  final def subscribe: SyncIO[Cancelable] = observable.subscribeSyncIO

  final def unsafeSubscribe(): Cancelable                    = observable.unsafeSubscribe()
  final def unsafeSubscribe(writer: RxWriter[A]): Cancelable = observable.unsafeSubscribe(writer.observer)
  final def unsafeForeach(f: A => Unit): Cancelable      = observable.unsafeForeach(f)

  final def hot: SyncIO[SelfSync[A]] = SyncIO(unsafeHot())
  final def unsafeHot(): SelfSync[A] = {
    val _ = unsafeSubscribe()
    selfRxSourceSync
  }

  final def map[B](f: A => B): SelfSync[B]                          = transformRxSourceSync(_.map(f))
  final def tap(f: A => Unit): SelfSync[A]                          = transformRxSourceSync(_.tap(f))

  final def collect[B](f: PartialFunction[A, B]): Self[B]       = transformRxSource(_.collect(f))
  final def mapEither[B](f: A => Either[Throwable, B]): Self[B] = transformRxSource(_.mapEither(f))
  final def drop(n: Int): Self[A] = transformRxSource(_.drop(n))

  final def mapSyncEffect[F[_]: RunSyncEffect, B](f: A => F[B]): SelfSync[B] = transformRxSourceSync(_.mapEffect(f))
  final def mapEffect[F[_]: RunEffect, B](f: A => F[B]): Self[B] = transformRxSource(_.mapEffect(f))
  final def mapFuture[B](f: A => Future[B]): Self[B]             = transformRxSource(_.mapFuture(f))

  final def as[B](value: B): SelfSync[B]        = transformRxSourceSync(_.as(value))
  final def asEval[B](value: => B): SelfSync[B] = transformRxSourceSync(_.asEval(value))

  final def asSyncEffect[F[_]: RunSyncEffect, B](value: F[B]): SelfSync[B] = transformRxSourceSync(_.asEffect(value))
  final def asEffect[F[_]: RunEffect, B](value: F[B]): Self[B] = transformRxSource(_.asEffect(value))
  final def asFuture[B](value: => Future[B]): Self[B]          = transformRxSource(_.asFuture(value))

  final def via(writer: RxWriter[A]): SelfSync[A] = transformRxSourceSync(_.via(writer.observer))

  final def switchMap[B](f: A => RxSource[B]): Self[B] = transformRxSource(_.switchMap(f andThen (_.observable)))
  final def mergeMap[B](f: A => RxSource[B]): Self[B]  = transformRxSource(_.mergeMap(f andThen (_.observable)))
  final def concatMap[B](f: A => RxSource[B]): Self[B] = transformRxSource(_.concatMap(f andThen (_.observable)))

  final def combineLatestMap[B, R](sourceB: RxSource[B])(f: (A, B) => R): Self[R] =
    transformRxSource(_.combineLatestMap(sourceB.observable)(f))
  final def combineLatest[B](sourceB: RxSource[B]): Self[(A, B)]                  =
    transformRxSource(_.combineLatest(sourceB.observable))

  final def withLatestMap[B, R](sourceB: RxSource[B])(f: (A, B) => R): Self[R] =
    transformRxSource(_.withLatestMap(sourceB.observable)(f))
  final def withLatest[B](sourceB: RxSource[B]): Self[(A, B)]                  =
    transformRxSource(_.withLatest(sourceB.observable))
}

object RxSource {
  implicit object source extends Source[RxSource] {
    def unsafeSubscribe[A](source: RxSource[A])(sink: Observer[A]): Cancelable = source.observable.unsafeSubscribe(sink)
  }

  @inline implicit final class RxSourceOps[A](val self: RxSource[A]) extends AnyVal {
    def scan[B](seed: => B)(f: (B, A) => B): self.SelfSync[B] = self.transformRxSourceSync(_.scan(seed)(f))

    def filter(f: A => Boolean): self.Self[A] = self.transformRxSource(_.filter(f))
  }

  @inline implicit final class RxSourceBooleanOps(val self: RxSource[Boolean]) extends AnyVal {
    @inline def toggle[A](ifTrue: => A, ifFalse: => A): self.SelfSync[A] = self.map {
      case true  => ifTrue
      case false => ifFalse
    }

    @inline def toggle[A: Monoid](ifTrue: => A): self.SelfSync[A] = toggle(ifTrue, Monoid[A].empty)

    @inline def negated: self.SelfSync[Boolean] = self.map(x => !x)
  }
}

trait RxEvent[+A] extends RxSource[A] {
  type Self[+X] = RxEvent[X]
  type SelfSync[+X] = RxEvent[X]

  final override def selfRxSourceSync: RxEvent[A] = this
  final override def transformRxSource[B](f: Observable[A] => Observable[B]): RxEvent[B]         = RxEvent.observable(f(observable))
  final override def transformRxSourceSync[B](f: Observable[A] => Observable[B]): RxEvent[B]     = RxEvent.observable(f(observable))
}

object RxEvent extends RxPlatform {
  private val _empty: RxEvent[Nothing] = observableUnshared(Observable.empty)
  def empty[A]: RxEvent[A] = _empty

  def apply[A](values: A*): RxEvent[A] = iterable(values)

  def iterable[A](values: Iterable[A]): RxEvent[A] = observableUnshared(Observable.fromIterable(values))

  def future[A](future: => Future[A]): RxEvent[A] = observable(Observable.fromFuture(future))
  def effect[F[_]: RunEffect, A](effect: F[A]): RxEvent[A] = observable(Observable.fromEffect(effect))

  def merge[A](rxs: RxEvent[A]*): RxEvent[A]  = observableUnshared(Observable.mergeIterable(rxs.map(_.observable)))
  def switch[A](rxs: RxEvent[A]*): RxEvent[A] = observableUnshared(Observable.switchIterable(rxs.map(_.observable)))
  def concat[A](rxs: RxEvent[A]*): RxEvent[A] = observableUnshared(Observable.concatIterable(rxs.map(_.observable)))

  def observable[A](observable: Observable[A]): RxEvent[A]         = observableUnshared(observable.publish.refCount)
  private def observableUnshared[A](observable: Observable[A]): RxEvent[A] = new RxEventObservable(observable)
}

trait RxState[+A] extends RxSource[A] {
  type Self[+X] = RxLater[X]
  type SelfSync[+X] <: RxState[X]

  final override def transformRxSource[B](f: Observable[A] => Observable[B]): RxLater[B] = RxLater.observable(f(observable))
}

trait RxLater[+A] extends RxState[A] {
  type SelfSync[+X] = RxLater[X]

  def apply()(implicit owner: LiveOwner): Option[A]
  def now()(implicit owner: NowOwner): Option[A]
  def nowIfSubscribedOption(): Option[Option[A]]

  final def nowIfSubscribedOpt(): Option[A] = nowIfSubscribedOption().getOrElse(throw RxMissingNowException)

  final override def selfRxSourceSync: RxLater[A] = this
  final override def transformRxSourceSync[B](f: Observable[A] => Observable[B]): RxLater[B] = RxLater.observable(f(observable))
}

object RxLater {
  def empty[A]: RxLater[A] = RxLaterEmpty

  def future[A](future: => Future[A]): RxLater[A] = observable(Observable.fromFuture(future))
  def effect[F[_]: RunEffect, A](effect: F[A]): RxLater[A] = observable(Observable.fromEffect(effect))

  def observable[A](observable: Observable[A]): RxLater[A] = new RxLaterObservable(observable)

  @inline implicit final class RxLaterOps[A](private val self: RxLater[A]) extends AnyVal {
    def toRx: Rx[Option[A]] = Rx.observableSeed(self.observable.map[Option[A]](Some.apply))(None)
    def toRx(seed: => A): Rx[A] = Rx.observableSeed(self.observable)(seed)
  }
}

trait Rx[+A] extends RxState[A] {
  type SelfSync[+X] = Rx[X]

  def apply()(implicit owner: LiveOwner): A
  def now()(implicit owner: NowOwner): A
  def nowIfSubscribedOption(): Option[A]

  final def nowIfSubscribed(): A = nowIfSubscribedOption().getOrElse(throw RxMissingNowException)

  final override def selfRxSourceSync: Rx[A] = this
  final override def transformRxSourceSync[B](f: Observable[A] => Observable[B]): Rx[B] = Rx.observableSync(f(observable))
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

trait VarEvent[A] extends RxWriter[A] with RxEvent[A] {
  final def transformVarEvent[A2](f: RxWriter[A] => RxWriter[A2])(g: RxEvent[A] => RxEvent[A2]): VarEvent[A2] =
    VarEvent.combine(g(this), f(this))
  final def transformVarEventRxEvent(g: RxEvent[A] => RxEvent[A]): VarEvent[A]                                = VarEvent.combine(g(this), this)
  final def transformVarEventRxWriter(f: RxWriter[A] => RxWriter[A]): VarEvent[A]                             = VarEvent.combine(this, f(this))
}

object VarEvent {
  def apply[A](): VarEvent[A]        = new VarEventSubject

  def subject[A](read: Subject[A]): VarEvent[A] = combine(RxEvent.observable(read), RxWriter.observer(read))

  def combine[A](read: RxEvent[A], write: RxWriter[A]): VarEvent[A] = new VarEventCombine(read, write)
}

trait VarState[A] extends RxWriter[A] with RxState[A]

trait VarLater[A] extends VarState[A] with RxLater[A] {
  final def transformVarLater[A2](f: RxWriter[A] => RxWriter[A2])(g: RxLater[A] => RxLater[A2]): VarLater[A2] = VarLater.combine(g(this), f(this))
  final def transformVarLaterRx(g: RxLater[A] => RxLater[A]): VarLater[A]                                     = VarLater.combine(g(this), this)
  final def transformVarLaterRxWriter(f: RxWriter[A] => RxWriter[A]): VarLater[A]                   = VarLater.combine(this, f(this))
}

object VarLater {
  def apply[A](): VarLater[A]        = new VarLaterSubject

  def subject[A](read: Subject[A]): VarLater[A] = combine(RxLater.observable(read), RxWriter.observer(read))

  def combine[A](read: RxLater[A], write: RxWriter[A]): VarLater[A] = new VarLaterCombine(read, write)
}

trait Var[A] extends VarState[A] with Rx[A] {
  final def updateIfSubscribed(f: A => A): Unit = set(f(nowIfSubscribed()))
  final def update(f: A => A): Unit             = set(f(now()))

  final def transformVar[A2](f: RxWriter[A] => RxWriter[A2])(g: Rx[A] => Rx[A2]): Var[A2] = Var.combine(g(this), f(this))
  final def transformVarRx(g: Rx[A] => Rx[A]): Var[A]                                     = Var.combine(g(this), this)
  final def transformVarRxWriter(f: RxWriter[A] => RxWriter[A]): Var[A]                   = Var.combine(this, f(this))

  def imap[A2](f: A2 => A)(g: A => A2): Var[A2]         = transformVar(_.contramap(f))(_.map(g))
  def lens[B](read: A => B)(write: (A, B) => A): Var[B] =
    transformVar(_.contramap(write(nowIfSubscribed(), _)))(_.map(read))

  def prism[A2](f: A2 => A)(g: A => Option[A2])(seed: => A2): Var[A2] =
    transformVar(_.contramap(f))(rx => Rx.observableSync(rx.observable.mapFilter(g).prependEval(seed)))

  def subType[A2 <: A: ClassTag](seed: => A2): Var[A2] = prism[A2]((x: A2) => x) {
    case a: A2 => Some(a)
    case _     => None
  }(seed)

  def imapO[B](optic: Iso[A, B]): Var[B]                = imap(optic.reverseGet(_))(optic.get(_))
  def lensO[B](optic: Lens[A, B]): Var[B]               = lens(optic.get(_))((base, zoomed) => optic.replace(zoomed)(base))
  def prismO[B](optic: Prism[A, B])(seed: => B): Var[B] =
    prism(optic.reverseGet(_))(optic.getOption(_))(seed)
}

object Var {
  def apply[A](seed: A): Var[A] = new VarSubject(seed)

  def subjectSync[A](read: Subject[A]): Var[A] = combine(Rx.observableSync(read), RxWriter.observer(read))

  def combine[A](read: Rx[A], write: RxWriter[A]): Var[A] = new VarCombine(read, write)

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
                Var.combine(Rx.const(a), RxWriter.observer(observer))
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

private final class RxEventObservable[A](val observable: Observable[A]) extends RxEvent[A]

private final object RxLaterEmpty extends RxLater[Nothing] {
  private lazy val someNone = Some(None)

  val observable             = Observable.empty

  def apply()(implicit owner: LiveOwner) = None
  def now()(implicit owner: NowOwner)    = None
  def nowIfSubscribedOption()                = someNone
}

private final class RxConst[A](value: A) extends Rx[A] {
  private lazy val someValue = Some(value)

  val observable: Observable[A]             = Observable.pure(value)

  def apply()(implicit owner: LiveOwner): A = value
  def now()(implicit owner: NowOwner): A    = value
  def nowIfSubscribedOption(): Option[A]                = someValue
}

private final class RxLaterWrap[A](state: Rx[A]) extends RxLater[A] {
  val observable: Observable[A] = state.observable

  def apply()(implicit owner: LiveOwner) = Some(state())
  def now()(implicit owner: NowOwner)       = Some(state.now())
  def nowIfSubscribedOption()                           = Some(state.nowIfSubscribedOption())
}

private final class RxLaterObservable[A](inner: Observable[A]) extends RxLater[A] {
  private val state = Rx.observableSeed(inner.map[Option[A]](Some.apply))(None)

  val observable: Observable[A] = state.observable.flattenOption

  def apply()(implicit owner: LiveOwner) = state()
  def now()(implicit owner: NowOwner)       = state.now()
  def nowIfSubscribedOption()                           = state.nowIfSubscribedOption()
}

private final class RxSyncObservable[A](inner: Observable[A]) extends Rx[A] {
  private val state = new ReplayLatestSubject[A]()

  val observable: Observable[A] =
    inner.dropUntilSyncLatest.distinctOnEquals.tapCancel(state.unsafeResetState).multicast(state).refCount

  def apply()(implicit owner: LiveOwner): A = owner.unsafeLive(this)
  def now()(implicit owner: NowOwner)       = owner.unsafeNow(this)
  def nowIfSubscribedOption()                           = state.now()
}

private final class RxWriterObserver[A](val observer: Observer[A]) extends RxWriter[A]

private final class VarEventSubject[A] extends VarEvent[A] {
  private val state             = new PublishSubject[A]
  val observable: Observable[A] = state
  val observer: Observer[A]     = state
}

private final class VarEventCombine[A](innerRead: RxEvent[A], innerWrite: RxWriter[A]) extends VarEvent[A] {
  val observable = innerRead.observable
  val observer   = innerWrite.observer
}

private final class VarLaterSubject[A]                                   extends VarLater[A] {
  private val state = Var[Option[A]](None)

  val observable: Observable[A] = state.observable.flattenOption
  val observer: Observer[A]     = state.observer.contramap(Some.apply)

  def nowIfSubscribedOption()                           = state.nowIfSubscribedOption()
  def now()(implicit owner: NowOwner)       = state.now()
  def apply()(implicit owner: LiveOwner) = state()
}

private final class VarLaterCombine[A](innerRead: RxLater[A], innerWrite: RxWriter[A]) extends VarLater[A] {
  val observable                            = innerRead.observable
  val observer                              = innerWrite.observer

  def nowIfSubscribedOption()                           = innerRead.nowIfSubscribedOption()
  def now()(implicit owner: NowOwner)       = innerRead.now()
  def apply()(implicit owner: LiveOwner) = innerRead.apply()
}

private final class VarSubject[A](seed: A)                                   extends Var[A] {
  private val state = new BehaviorSubject[A](seed)

  val observable: Observable[A] = state.distinctOnEquals
  val observer: Observer[A]     = state

  def nowIfSubscribedOption()                           = Some(state.now())
  def now()(implicit owner: NowOwner)       = state.now()
  def apply()(implicit owner: LiveOwner): A = owner.unsafeLive(this)
}

private final class VarCombine[A](innerRead: Rx[A], innerWrite: RxWriter[A]) extends Var[A] {
  val observable                            = innerRead.observable
  val observer                              = innerWrite.observer

  def nowIfSubscribedOption()                           = innerRead.nowIfSubscribedOption()
  def now()(implicit owner: NowOwner)       = innerRead.now()
  def apply()(implicit owner: LiveOwner): A = innerRead()
}
