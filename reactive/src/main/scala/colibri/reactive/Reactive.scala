package colibri.reactive

import cats.Functor
import cats.effect.IO
import colibri._
import colibri.effect._
import colibri.helpers.JSArrayHelper

import scala.scalajs.js
import scala.concurrent.Future
import cats.implicits._

case class Tx[+A](value: A, fire: IO[Boolean]) {
  final def map[B](f: A => B): Tx[B]                           = copy[B](value = f(value))
  final def traverse[F[_]: Functor, B](f: A => F[B]): F[Tx[B]] = f(value).map(b => copy[B](value = b))
}
object Tx                                      {
  def single[A](value: A): Tx[A] = Tx(value, IO.pure(true))
}

trait Rx[+A] { self =>
  def now(): A
  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit

  final def trigger(writer: RxWriter[A])(implicit owner: Owner): Unit = {
    writer() = now()
    triggerLater(writer)
  }

  final def foreachLater(f: A => Unit)(implicit owner: Owner): Unit = triggerLater(RxWriter.foreach(f))
  final def foreach(f: A => Unit)(implicit owner: Owner): Unit      = trigger(RxWriter.foreach(f))

  final def map[B](f: A => B)(implicit owner: Owner): Rx[B] = {
    // This is equivalent to: transformObservableTx(_.map(_.map(f)))
    // But we create a specialized implementation for performance.
    val variable = Var(f(self.now()))
    self.triggerLater(variable.contramap(f))
    variable
  }

  final def mapFilter[B](f: A => Option[B])(seed: => B)(implicit owner: Owner): Rx[B] = {
    // This is equivalent to: transformObservableTx(_.map(_.traverse(f)))
    // But we create a specialized implementation for performance.
    val variable = Var(f(self.now()).getOrElse(seed))
    self.triggerLater(variable.contramapFilter(f))
    variable
  }

  final def collect[B](f: PartialFunction[A, B])(seed: => B)(implicit owner: Owner): Rx[B] = mapFilter(f.lift)(seed)

  final def tap(f: A => Unit)(implicit owner: Owner): Rx[A] = map { a => f(a); a }

  final def as[B](value: B)(implicit owner: Owner): Rx[B]        = map(_ => value)
  final def asEval[B](value: => B)(implicit owner: Owner): Rx[B] = map(_ => value)

  final def mapSyncEffect[F[_]: RunSyncEffect: Functor, B](f: A => F[B])(implicit owner: Owner) =
    transformObservableTx(_.mapEffect(_.traverse(f)))

  final def mapEffect[F[_]: RunEffect: Functor, B](f: A => F[B])(seed: => B)(implicit owner: Owner) =
    transformObservableTx(_.mapEffect(_.traverse(f)).prepend(Tx.single(seed)))

  final def mapFuture[B](f: A => Future[B])(seed: => B)(implicit owner: Owner) =
    mapEffect(a => IO.fromFuture(IO(f(a))))(seed)

  final def asSyncEffect[F[_]: RunSyncEffect: Functor, B](f: F[B])(implicit owner: Owner) =
    mapSyncEffect(_ => f)

  final def asEffect[F[_]: RunEffect: Functor, B](f: F[B])(seed: => B)(implicit owner: Owner) =
    mapEffect(_ => f)(seed)

  final def asFuture[B](f: => Future[B])(seed: => B)(implicit owner: Owner) =
    mapFuture(_ => f)(seed)

  final def switchMap[B](f: A => Rx[B])(implicit owner: Owner): Rx[B] = Rx.function(implicit owner => f(self())())

  final def toObservable: Observable[A] = Observable.lift(this)(Rx.source)

  final def toObservableTx: Observable[Tx[A]] = Observable.create[Tx[A]] { observer =>
    Owner.unsafeHotCancelable { implicit owner =>
      self.trigger(RxWriter { a =>
        val subject = Subject.replayLatest[Boolean]()
        observer.unsafeOnNext(Tx(a, subject.headIO))
        Fire(() => subject.unsafeOnNext(true), () => subject.unsafeOnNext(false))
      })
    }
  }

  final def transformObservable[B](f: Observable[A] => Observable[B])(implicit owner: Owner): Rx[B] =
    Rx.observable(f(toObservable))

  final def transformObservableTx[B](f: Observable[Tx[A]] => Observable[Tx[B]])(implicit owner: Owner): Rx[B] =
    Rx.observableTx(f(toObservableTx))

  final def apply()(implicit liveOwner: LiveOwner): A = liveOwner.unsafeLive(this)
}

object Rx extends RxPlatform {
  def function[R](f: LiveOwner => R)(implicit owner: Owner): Rx[R] = {
    val subject = Subject.behavior(())
    val writer  = RxWriter.foreach[Any](_ => subject.unsafeOnNext(())).zipFire

    val observable = subject.switchMap { _ =>
      implicit val owner: LiveOwner = LiveOwner.unsafeRef()
      val _                         = owner.unsafeSubscribe()
      val result                    = f(owner)

      owner.unsafeLiveRxArray.foreach(_.triggerLater(writer))
      Observable[R](result).tapCancel(owner.cancelable.unsafeCancel)
    }

    Rx.observable(observable)
  }

  def const[A](value: A): Rx[A] = new RxConst(value)

  def syncEffect[F[_]: RunSyncEffect, A](effect: F[A])(implicit owner: Owner): Rx[A] =
    observable(Observable.fromEffect(effect))

  def future[A](future: Future[A])(seed: => A)(implicit owner: Owner): Rx[A] =
    observable(Observable.fromFuture(future).prepend(seed))

  def effect[F[_]: RunEffect, A](effect: F[A])(seed: => A)(implicit owner: Owner): Rx[A] =
    observable(Observable.fromEffect(effect).prepend(seed))

  def observable[A](observable: Observable[A])(implicit owner: Owner): Rx[A] = observableTx(observable.map(Tx.single))

  def observableTx[A](observable: Observable[Tx[A]])(implicit owner: Owner): Rx[A] = new RxObservableTx(observable)

  @inline implicit final class RxOps[A](private val self: Rx[A]) extends AnyVal {
    def filter(f: A => Boolean)(seed: => A)(implicit owner: Owner): Rx[A] = self.mapFilter { a => if (f(a)) Some(a) else None }(seed)

    def filterOrNow(f: A => Boolean)(implicit owner: Owner): Rx[A] = self.mapFilter { a => if (f(a)) Some(a) else None }(self.now())

    def scan(f: (A, A) => A)(implicit owner: Owner): Rx[A] = scan(self.now())(f)

    def scan[B](seed: B)(f: (B, A) => B)(implicit owner: Owner): Rx[B] = scanf(_ => seed)(f)

    def scanf[B](seed: A => B)(f: (B, A) => B)(implicit owner: Owner): Rx[B] = {
      var current: Option[B] = None
      self.map { a =>
        val now    = current.getOrElse(seed(a))
        val result = f(now, a)
        current = Some(result)
        result
      }
    }
  }

  implicit object source extends Source[Rx] {
    def unsafeSubscribe[A](source: Rx[A])(sink: Observer[A]): Cancelable = Owner.unsafeHotCancelable { owner =>
      val _ = owner.unsafeSubscribe()
      source.foreach(sink.unsafeOnNext)(owner)
    }
  }
}

trait RxWriter[-A] { self =>
  def setValue(value: A): Fire

  @deprecated("Use variable() = value (that is: variable.update(value)) instead", "0.6.0")
  final def set(value: A): Unit = update(value)

  final def update(value: A): Unit = {
    val fire = setValue(value)
    fire.commit()
  }

  final def contramap[B](f: B => A): RxWriter[B] =
    RxWriter(b => self.setValue(f(b)))

  final def contramapFilter[B](f: B => Option[A]): RxWriter[B] =
    RxWriter(b => f(b).fold(Fire.empty)(self.setValue))

  final def contramapCollect[B](f: PartialFunction[B, A]): RxWriter[B] = contramapFilter(f.lift)

  final def zipFire: RxWriter[A] = new RxWriterZipFire(self)

  final def toObserver: Observer[A] = Observer.lift(this)(RxWriter.sink)
}

object RxWriter {
  case class Setter[A](writer: RxWriter[A], value: A) {
    def setValue() = writer.setValue(value)
  }
  object Setter                                       {
    implicit def tupleToSetter[A](tuple: (RxWriter[A], A)): Setter[A] = Setter(tuple._1, tuple._2)
  }

  def update(setters: Setter[_]*): Unit = {
    val fires = setters.map(_.setValue())
    fires.foreach(_.commit())
  }

  def apply[A](f: A => Fire): RxWriter[A] = new RxWriter[A] {
    def setValue(value: A): Fire = f(value)
  }

  def foreach[A](f: A => Unit): RxWriter[A] = apply(a => Fire.onCommit(() => f(a)))

  def observer[A](observer: Observer[A]): RxWriter[A] = {
    var lastValue = Option.empty[A]
    RxWriter { value =>
      lastValue = Some(value)

      Fire.onCommit { () =>
        lastValue.foreach(observer.unsafeOnNext(_))
        lastValue = None
      }
    }
  }

  implicit object sink extends Sink[RxWriter] {
    @inline def unsafeOnNext[A](sink: RxWriter[A])(value: A): Unit          = sink() = value
    @inline def unsafeOnError[A](sink: RxWriter[A])(error: Throwable): Unit =
      helpers.UnhandledErrorReporter.errorSubject.unsafeOnNext(error)
  }

  @inline implicit final class RxOps[A](private val self: RxWriter[A]) extends AnyVal {
    def contrafilter(f: A => Boolean): RxWriter[A] = self.contramapFilter { a => if (f(a)) Some(a) else None }
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

private final class RxObservableTx[A](inner: Observable[Tx[A]])(implicit owner: Owner) extends Rx[A] {
  private val state = Subject.replayLatest[Tx[A]]()
  owner.unsafeOwn(() => inner.unsafeSubscribe(state))

  def now() = state.now().get.value

  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit = {
    owner.unsafeOwnLater { () =>
      writer() = now()
      Cancelable.empty
    }
    owner.unsafeOwn { () =>
      state.distinctOnEquals.dropSyncAll
        .mapEffect { tx =>
          val fire = writer.setValue(tx.value)
          tx.fire.map {
            case true  => fire.commit()
            case false => fire.forget()
          }
        }
        .unsafeSubscribe()
    }
  }
}

private final class VarSubject[A](seed: A) extends Var[A] {
  private var subscribers = new js.Array[RxWriter[A]]
  private var isRunning   = false

  private var openTransactions    = 0
  private var committedCurrent: A = seed
  private var current: A          = seed

  def hasSubscribers: Boolean = subscribers.nonEmpty

  def setValue(value: A): Fire = {
    if (value != current) {
      current = value
      committedCurrent = null.asInstanceOf[A]
      openTransactions += 1

      val running = isRunning
      isRunning = true
      val fires   = subscribers.map(_.setValue(current))
      isRunning = running

      Fire(
        { () =>
          openTransactions -= 1
          if (openTransactions == 0 && committedCurrent != current) {
            fires.foreach(_.commit())
            committedCurrent = current
          } else {
            fires.foreach(_.forget())
          }
        },
        { () => fires.foreach(_.forget()) },
      )
    } else Fire.empty
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
  private var openTransactions = 0

  def setValue(value: A): Fire = {
    openTransactions += 1
    val fire = inner.setValue(value)

    Fire(
      { () =>
        openTransactions -= 1
        if (openTransactions == 0) fire.commit()
        else fire.forget()
      },
      { () =>
        openTransactions -= 1
        fire.forget()
      },
    )
  }
}

private final class VarCombine[A](innerRead: Rx[A], innerWrite: RxWriter[A]) extends Var[A] {
  def now()                                                          = innerRead.now()
  def triggerLater(writer: RxWriter[A])(implicit owner: Owner): Unit = innerRead.triggerLater(writer)
  def setValue(value: A): Fire                                       = innerWrite.setValue(value)
}
