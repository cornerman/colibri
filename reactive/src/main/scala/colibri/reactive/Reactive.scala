package colibri.reactive

import colibri._
import colibri.effect._
import colibri.helpers.JSArrayHelper

import scala.scalajs.js
import scala.concurrent.Future
import cats.implicits._

trait Tx
object Tx {
  def create(): Tx = new Tx {}
}

trait Rx[+A] { self =>
  def now(): A
  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit

  final def trigger(writer: RxWriter[A])(implicit owner: Owner): Unit = {
    writer() = now()
    triggerLater(writer)
  }

  final def map[B](f: A => B)(implicit owner: Owner): Rx[B] = {
    val variable = Var(f(self.now()))
    self.triggerLater(variable.contramap(f))
    variable
  }

  final def tap(f: A => Unit)(implicit owner: Owner): Rx[A] = map { a => f(a); a }

  final def mapFilter[B](f: A => Option[B])(implicit owner: Owner): Rx[Option[B]]      = map(a => f(a))
  final def collect[B](f: PartialFunction[A, B])(implicit owner: Owner): Rx[Option[B]] = map(a => f.lift(a))

  final def as[B](value: B)(implicit owner: Owner): Rx[B]        = map(_ => value)
  final def asEval[B](value: => B)(implicit owner: Owner): Rx[B] = map(_ => value)

  final def foreachFire(f: () => Unit)(implicit owner: Owner): Unit      = trigger(RxWriter.fire(f))
  final def foreachFireLater(f: () => Unit)(implicit owner: Owner): Unit = triggerLater(RxWriter.fire(f))

  final def foreach(f: A => Unit)(implicit owner: Owner): Unit      = foreachFire(() => f(now()))
  final def foreachLater(f: A => Unit)(implicit owner: Owner): Unit = foreachFireLater(() => f(now()))

  final def switchMap[B](f: A => Rx[B])(implicit owner: Owner): Rx[B] = Rx.function(implicit owner => f(self())())

  final def toObservable: Observable[A] = Observable.lift(this)(Rx.source)

  final def apply()(implicit liveOwner: LiveOwner): A = liveOwner.unsafeLive(this)
}

object Rx extends RxPlatform {
  def function[R](f: LiveOwner => R)(implicit owner: Owner): Rx[R] = {
    val subject = Subject.behavior(())
    val writer  = RxWriter.fire(() => subject.unsafeOnNext(())).zipFire

    val observable = subject.switchMap { _ =>
      implicit val owner: LiveOwner = LiveOwner.unsafeHotRef()
      val _                         = owner.unsafeSubscribe()
      val result                    = f(owner)

      owner.unsafeLiveRxArray.foreach(_.triggerLater(writer))
      Observable[R](result).tapCancel(owner.cancelable.unsafeCancel)
    }

    Rx.observable(observable)
  }

  def const[A](value: A): Rx[A] = new RxConst(value)

  def syncEffect[F[_]: RunSyncEffect, A](effect: F[A])(implicit owner: Owner): Rx[A] =
    new RxObservable(Observable.fromEffect(effect))

  def future[A](future: Future[A])(implicit owner: Owner): Rx[Option[A]]             =
    new RxObservable(Observable.fromFuture(future).map[Option[A]](Some.apply).prepend(None))
  def effect[F[_]: RunEffect, A](effect: F[A])(implicit owner: Owner): Rx[Option[A]] =
    new RxObservable(Observable.fromEffect(effect).map[Option[A]](Some.apply).prepend(None))

  def observable[A](observable: Observable[A])(implicit owner: Owner): Rx[A] = new RxObservable(observable)

  @inline implicit final class RxOps[A](private val self: Rx[A]) extends AnyVal {
    def scan(f: (A, A) => A)(implicit owner: Owner): Rx[A] = scan(self.now())(f)

    def scan[B](seed: B)(f: (B, A) => B)(implicit owner: Owner): Rx[B] = {
      var current = seed
      self.map { a =>
        current = f(current, a)
        current
      }
    }

    def filter(f: A => Boolean)(implicit owner: Owner): Rx[Option[A]] = self.map { a => if (f(a)) Some(a) else None }
  }

  implicit object source extends Source[Rx] {
    def unsafeSubscribe[A](source: Rx[A])(sink: Observer[A]): Cancelable = {
      val owner = Owner.unsafeHotRef()
      val _     = owner.unsafeSubscribe()
      source.foreach(sink.unsafeOnNext)(owner)
      owner.cancelable
    }
  }
}

trait RxWriter[-A] { self =>
  def setValue(value: A)(implicit tx: Tx): Unit
  def fire()(implicit tx: Tx): Unit

  @deprecated("Use variable() = value (that is: variable.update(value)) instead", "0.6.0")
  final def set(value: A): Unit = update(value)

  final def update(value: A): Unit = {
    implicit val tx: Tx = Tx.create()
    setValue(value)
    fire()
  }

  final def contramap[B](f: B => A): RxWriter[B] =
    RxWriter.createTx(implicit tx => b => self.setValue(f(b)), implicit tx => () => self.fire())

  final def zipFire: RxWriter[A] = new RxWriterZipFire(self)

  final def toObserver: Observer[A] = Observer.lift(this)(RxWriter.sink)
}

object RxWriter {
  case class Setter[A](writer: RxWriter[A], value: A) {
    def setValue()(implicit tx: Tx) = writer.setValue(value)
    def fire()(implicit tx: Tx)     = writer.fire()
  }
  object Setter                                       {
    implicit def tupleToSetter[A](tuple: (RxWriter[A], A)): Setter[A] = Setter(tuple._1, tuple._2)
  }

  def update(setters: Setter[_]*): Unit = {
    implicit val tx: Tx = Tx.create()
    setters.foreach(_.setValue())
    setters.foreach(_.fire())
  }

  // TODO implicit functions type and macro like Rx to hide the Tx parameter
  def createTx[A](onSet: Tx => (A => Unit) = (_: Tx) => (_: A) => (), onFire: Tx => (() => Unit) = _ => () => ()): RxWriter[A] =
    new RxWriter[A] {
      def setValue(value: A)(implicit tx: Tx): Unit = onSet(tx)(value)
      def fire()(implicit tx: Tx): Unit             = onFire(tx)()
    }

  def create[A](onSet: A => Unit = (_: A) => (), onFire: () => Unit = () => ()): RxWriter[A] = createTx[A](_ => onSet, _ => onFire)

  def foreach[A](f: A => Unit): RxWriter[A]           = create(f)
  def foreachTx[A](f: Tx => (A => Unit)): RxWriter[A] = createTx[A](f)
  def fire[A](f: () => Unit): RxWriter[A]             = create(onFire = f)
  def fireTx[A](f: Tx => () => Unit): RxWriter[A]     = createTx(onFire = f)

  def observer[A](observer: Observer[A]): RxWriter[A] = {
    var lastValue = Option.empty[A]
    RxWriter.create(
      value => lastValue = Some(value),
      { () =>
        lastValue.foreach(observer.unsafeOnNext(_))
        lastValue = None
      },
    )
  }

  implicit object sink extends Sink[RxWriter] {
    @inline def unsafeOnNext[A](sink: RxWriter[A])(value: A): Unit          = sink() = value
    @inline def unsafeOnError[A](sink: RxWriter[A])(error: Throwable): Unit =
      helpers.UnhandledErrorReporter.errorSubject.unsafeOnNext(error)
  }
}

trait Var[A] extends Rx[A] with RxWriter[A] {
  final def transformVar[A2](f: RxWriter[A] => RxWriter[A2])(g: Rx[A] => Rx[A2]): Var[A2] = Var.combine(g(this), f(this))
  final def transformVarRx(g: Rx[A] => Rx[A]): Var[A]                                     = Var.combine(g(this), this)
  final def transformVarRxWriter(f: RxWriter[A] => RxWriter[A]): Var[A]                   = Var.combine(this, f(this))
  final def imap[A2](f: A2 => A)(g: A => A2)(implicit owner: Owner): Var[A2]              = transformVar(_.contramap(f))(_.map(g))
  final def lens[B](read: A => B)(write: (A, B) => A)(implicit owner: Owner): Var[B]      =
    transformVar(_.contramap(write(now(), _)))(_.map(read))
}

object Var {
  def apply[A](seed: A): Var[A] = new VarSubject(seed)

  def combine[A](read: Rx[A], write: RxWriter[A]): Var[A] = new VarCombine(read, write)
}

private final class RxConst[A](value: A) extends Rx[A] {
  def now(): A                                                       = value
  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit = ()
}

private final class RxObservable[A](inner: Observable[A])(implicit owner: Owner) extends Rx[A] {
  private val state = Subject.replayLatest[A]()
  owner.unsafeOwn(() => inner.distinctOnEquals.unsafeSubscribe(state))

  def now() = state.now().get

  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit = {
    owner.unsafeOwnLater { () => writer() = now(); Cancelable.empty }
    owner.unsafeOwn { () => state.dropSyncAll.unsafeForeach(writer() = _) }
  }
}

private final class VarSubject[A](seed: A) extends Var[A] {
  private val transactions = new js.Array[Tx]
  private var subscribers  = new js.Array[RxWriter[A]]
  private var isRunning    = false

  private var committedCurrent: A = seed
  private var current: A          = seed

  def hasSubscribers: Boolean = subscribers.nonEmpty

  def setValue(value: A)(implicit tx: Tx): Unit = {
    if (transactions.indexOf(tx) == -1) transactions.push(tx)
    if (value != current) {
      current = value
      // committedCurrent = null.asInstanceOf[A]

      val running = isRunning
      isRunning = true
      subscribers.foreach(_.setValue(current))
      isRunning = running
    }
  }

  def fire()(implicit tx: Tx): Unit = {
    val idx = transactions.indexOf(tx)
    if (idx != -1) {
      transactions.splice(idx, 1)
      if (transactions.isEmpty && committedCurrent != current) {
        val running = isRunning
        isRunning = true
        subscribers.foreach(_.fire())
        isRunning = running
        committedCurrent = current
      }
    }
  }

  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit = {
    owner.unsafeOwnLater { () => writer() = now(); Cancelable.empty }
    owner.unsafeOwn { () =>
      subscribers.push(writer)
      Cancelable { () =>
        if (isRunning) subscribers = JSArrayHelper.removeElementCopied(subscribers)(writer)
        else JSArrayHelper.removeElement(subscribers)(writer)
      }
    }
  }

  def now(): A = current
}

private final class RxWriterZipFire[A](inner: RxWriter[A]) extends RxWriter[A] {
  private val transactions = new js.Array[Tx]

  def setValue(value: A)(implicit tx: Tx): Unit = {
    if (transactions.indexOf(tx) == -1) transactions.push(tx)
    inner.setValue(value)
  }

  def fire()(implicit tx: Tx): Unit = {
    val idx = transactions.indexOf(tx)
    if (idx != -1) {
      transactions.splice(idx, 1)
      if (transactions.isEmpty) inner.fire()
    }
  }
}

private final class VarCombine[A](innerRead: Rx[A], innerWrite: RxWriter[A]) extends Var[A] {
  def now()                                                          = innerRead.now()
  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit = innerRead.triggerLater(writer)
  def setValue(value: A)(implicit tx: Tx): Unit                      = innerWrite.setValue(value)
  def fire()(implicit tx: Tx): Unit                                  = innerWrite.fire()
}
