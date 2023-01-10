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
    extends Exception("Missing current value inside an Rx. Make sure, the Rx has active subscriptions when calling nowIfSubscribed.")

trait Rx[+A] {
  def observable: Observable[A]
  def nowOption(): Option[A]

  def apply()(implicit owner: LiveOwner): A
  def now()(implicit owner: NowOwner): A

  final def nowIfSubscribed(): A = nowOption().getOrElse(throw RxMissingNowException)

  final def collect[B](f: PartialFunction[A, B])(seed: => B): Rx[B] = transformRx(_.collect(f))(seed)
  final def map[B](f: A => B): Rx[B]                                = transformRxSync(_.map(f))
  final def mapEither[B](f: A => Either[Throwable, B]): Rx[B]       = transformRxSync(_.mapEither(f))
  final def tap(f: A => Unit): Rx[A]                                = transformRxSync(_.tap(f))
  final def tapLater(f: A => Unit): Rx[A]                           = transformRxSync(_.tail.tap(f))

  final def mapSyncEffect[F[_]: RunSyncEffect, B](f: A => F[B]): Rx[B]     = transformRxSync(_.mapEffect(f))
  final def mapEffect[F[_]: RunEffect, B](f: A => F[B])(seed: => B): Rx[B] = transformRx(_.mapEffect(f))(seed)
  final def mapFuture[B](f: A => Future[B])(seed: => B): Rx[B]             = transformRx(_.mapFuture(f))(seed)

  final def as[B](value: B): Rx[B]        = transformRxSync(_.as(value))
  final def asEval[B](value: => B): Rx[B] = transformRxSync(_.asEval(value))

  final def asSyncEffect[F[_]: RunSyncEffect, B](value: F[B]): Rx[B]     = transformRxSync(_.asEffect(value))
  final def asEffect[F[_]: RunEffect, B](value: F[B])(seed: => B): Rx[B] = transformRx(_.asEffect(value))(seed)
  final def asFuture[B](value: => Future[B])(seed: => B): Rx[B]          = transformRx(_.asFuture(value))(seed)

  final def via(writer: RxWriter[A]): Rx[A] = transformRxSync(_.via(writer.observer))

  final def switchMap[B](f: A => Rx[B]): Rx[B] = transformRxSync(_.switchMap(f andThen (_.observable)))
  final def mergeMap[B](f: A => Rx[B]): Rx[B]  = transformRxSync(_.mergeMap(f andThen (_.observable)))

  final def transformRx[B](f: Observable[A] => Observable[B])(seed: => B): Rx[B] = Rx.observable(f(observable))(seed)
  final def transformRxSync[B](f: Observable[A] => Observable[B]): Rx[B]         = Rx.observableSync(f(observable))

  final def hot: SyncIO[Rx[A]] = SyncIO(unsafeHot())

  final def unsafeHot(): Rx[A] = {
    val _ = unsafeSubscribe()
    this
  }

  final def subscribe: SyncIO[Cancelable] = observable.subscribeSyncIO

  final def unsafeSubscribe(): Cancelable                    = observable.unsafeSubscribe()
  final def unsafeSubscribe(writer: RxWriter[A]): Cancelable = observable.unsafeSubscribe(writer.observer)

  final def unsafeForeach(f: A => Unit): Cancelable      = observable.unsafeForeach(f)
  final def unsafeForeachLater(f: A => Unit): Cancelable = observable.tail.unsafeForeach(f)
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

  def observable[A](observable: Observable[A])(seed: => A): Rx[A] = observableSync(observable.prependEval(seed))

  def observableSync[A](observable: Observable[A]): Rx[A] = new RxObservableSync(observable)

  @inline implicit final class RxOps[A](private val self: Rx[A]) extends AnyVal {
    def scan[B](seed: B)(f: (B, A) => B): Rx[B] = self.transformRxSync(_.scan0(seed)(f))

    def filter(f: A => Boolean)(seed: => A): Rx[A] = self.transformRx(_.filter(f))(seed)
  }

  @inline implicit class RxBooleanOps(private val source: Rx[Boolean]) extends AnyVal {
    @inline def toggle[A](ifTrue: => A, ifFalse: => A): Rx[A] = source.map {
      case true  => ifTrue
      case false => ifFalse
    }

    @inline def toggle[A: Monoid](ifTrue: => A): Rx[A] = toggle(ifTrue, Monoid[A].empty)

    @inline def negated: Rx[Boolean] = source.map(x => !x)
  }

  implicit object source extends Source[Rx] {
    def unsafeSubscribe[A](source: Rx[A])(sink: Observer[A]): Cancelable = source.observable.unsafeSubscribe(sink)
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

trait Var[A] extends RxWriter[A] with Rx[A] {
  final def updateIfSubscribed(f: A => A): Unit = set(f(nowIfSubscribed()))
  final def update(f: A => A): Unit             = set(f(now()))

  final def transformVar[A2](f: RxWriter[A] => RxWriter[A2])(g: Rx[A] => Rx[A2]): Var[A2] = Var.combine(g(this), f(this))
  final def transformVarRx(g: Rx[A] => Rx[A]): Var[A]                                     = Var.combine(g(this), this)
  final def transformVarRxWriter(f: RxWriter[A] => RxWriter[A]): Var[A]                   = Var.combine(this, f(this))

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

  def none[A](): Var[Option[A]]        = new VarSubject(None)
  def some[A](seed: A): Var[Option[A]] = new VarSubject(Some(seed))

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

private final class RxConst[A](value: A) extends Rx[A] {
  val observable: Observable[A]             = Observable.pure(value)
  def nowOption(): Option[A]                = Some(value)
  def now()(implicit owner: NowOwner): A    = value
  def apply()(implicit owner: LiveOwner): A = value
}

private final class RxObservableSync[A](inner: Observable[A]) extends Rx[A] {
  private val state = new ReplayLatestSubject[A]()

  val observable: Observable[A] =
    inner.dropUntilSyncLatest.distinctOnEquals.tapCancel(state.unsafeResetState).multicast(state).refCount.distinctOnEquals

  def nowOption()                           = state.now()
  def now()(implicit owner: NowOwner)       = owner.unsafeNow(this)
  def apply()(implicit owner: LiveOwner): A = owner.unsafeLive(this)
}

private final class RxWriterObserver[A](val observer: Observer[A]) extends RxWriter[A]

private final class VarSubject[A](seed: A)                                   extends Var[A] {
  private val state = new BehaviorSubject[A](seed)

  val observable: Observable[A] = state.distinctOnEquals
  val observer: Observer[A]     = state

  def nowOption()                           = Some(state.now())
  def now()(implicit owner: NowOwner)       = state.now()
  def apply()(implicit owner: LiveOwner): A = owner.unsafeLive(this)
}
private final class VarCombine[A](innerRead: Rx[A], innerWrite: RxWriter[A]) extends Var[A] {
  def nowOption()                           = innerRead.nowOption()
  def now()(implicit owner: NowOwner)       = innerRead.now()
  def apply()(implicit owner: LiveOwner): A = innerRead()
  val observable                            = innerRead.observable
  val observer                              = innerWrite.observer
}
