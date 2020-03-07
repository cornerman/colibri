package colibri

import colibri.effect._

import cats.{ MonoidK, Functor, FunctorFilter, Eq }
import cats.effect.{ Effect, IO }

import scala.scalajs.js
import org.scalajs.dom

import scala.util.control.NonFatal
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

trait Observable[+A] {
  //TODO: def subscribe[G[_]: Sink, F[_] : Sync](sink: G[_ >: A]): F[Cancelable]
  def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable
}
object Observable {

  implicit object source extends Source[Observable] {
    @inline def subscribe[G[_]: Sink, A](source: Observable[A])(sink: G[_ >: A]): Cancelable = source.subscribe(sink)
  }

  implicit object liftSource extends LiftSource[Observable] {
    @inline def lift[G[_]: Source, A](source: G[A]): Observable[A] = Observable.lift[G, A](source)
  }

  implicit object monoidK extends MonoidK[Observable] {
    @inline def empty[T] = Observable.empty
    @inline def combineK[T](a: Observable[T], b: Observable[T]) = Observable.mergeVaried(a, b)
  }

  implicit object functor extends Functor[Observable] {
    @inline def map[A, B](fa: Observable[A])(f: A => B): Observable[B] = Observable.map(fa)(f)
  }

  implicit object functorFilter extends FunctorFilter[Observable] {
    @inline def functor = Observable.functor
    @inline def mapFilter[A, B](fa: Observable[A])(f: A => Option[B]): Observable[B] = Observable.mapFilter(fa)(f)
  }

  implicit object createSubject extends CreateSubject[Subject] {
    @inline def publish[A]: Subject[A] = Subject.publish[A]
    @inline def replay[A]: Subject[A] = Subject.replay[A]
    @inline def behavior[A](seed: A): Subject[A] = Subject.behavior[A](seed)
  }

  implicit object createProSubject extends CreateProSubject[ProSubject] {
    @inline def from[SI[_] : Sink, SO[_] : Source, I,O](sink: SI[I], source: SO[O]): ProSubject[I, O] = ProSubject.from(sink, source)
  }

  trait Connectable[+A] extends Observable[A] {
    def connect(): Cancelable
  }
  trait Value[+A] extends Observable[A] {
    def now(): A
  }
  trait MaybeValue[+A] extends Observable[A] {
    def now(): Option[A]
  }

  type ConnectableValue[+A] = Connectable[A] with Value[A]
  type ConnectableMaybeValue[+A] = Connectable[A] with MaybeValue[A]

  type Hot[+A] = Observable[A] with Cancelable
  type HotValue[+A] = Value[A] with Cancelable
  type HotMaybeValue[+A] = MaybeValue[A] with Cancelable

  final class Synchronous[+A] private[colibri](source: Observable[A]) extends Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = source.subscribe(sink)
  }

  object Empty extends Observable[Nothing] {
    @inline def subscribe[G[_]: Sink](sink: G[_ >: Nothing]): Cancelable = Cancelable.empty
  }

  @inline def empty = Empty

  def apply[T](value: T): Observable[T] = new Observable[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Cancelable = {
      Sink[G].onNext(sink)(value)
      Cancelable.empty
    }
  }

  def fromIterable[T](values: Iterable[T]): Observable[T] = new Observable[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Cancelable = {
      values.foreach(Sink[G].onNext(sink))
      Cancelable.empty
    }
  }

  def lift[F[_]: Source, A](source: F[A]): Observable[A] = source match {
    case source: Observable[A@unchecked] => source
    case _ => new Observable[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = Source[F].subscribe(source)(sink)
    }
  }

  @inline def create[A](produce: Observer[A] => Cancelable): Observable[A] = createLift[Observer, A](produce)

  def createLift[F[_]: Sink: LiftSink, A](produce: F[_ >: A] => Cancelable): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = produce(LiftSink[F].lift(sink))
  }

  def fromEither[A](value: Either[Throwable, A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      value match {
        case Right(a) => Sink[G].onNext(sink)(a)
        case Left(error) => Sink[G].onError(sink)(error)
      }
      Cancelable.empty
    }
  }

  def fromSync[F[_]: RunSyncEffect, A](effect: F[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      recovered(Sink[G].onNext(sink)(RunSyncEffect[F].unsafeRun(effect)), Sink[G].onError(sink)(_))
      Cancelable.empty
    }
  }

  def fromAsync[F[_]: Effect, A](effect: F[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      //TODO: proper cancel effects?
      var isCancel = false

      Effect[F].runAsync(effect)(either => IO {
        if (!isCancel) either match {
          case Right(value) => Sink[G].onNext(sink)(value)
          case Left(error)  => Sink[G].onError(sink)(error)
        }
      }).unsafeRunSync()

      Cancelable(() => isCancel = true)
    }
  }

  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): Observable[A] = fromAsync(IO.fromFuture(IO.pure(future))(IO.contextShift(ec)))

  def ofEvent[EV <: dom.Event](target: dom.EventTarget, eventType: String): Synchronous[EV] = new Synchronous(new Observable[EV] {
    def subscribe[G[_] : Sink](sink: G[_ >: EV]): Cancelable = {
      var isCancel = false

      val eventHandler: js.Function1[EV, Unit] = { v =>
        if (!isCancel) {
          Sink[G].onNext(sink)(v)
        }
      }

      def register() = target.addEventListener(eventType, eventHandler)
      def unregister() = if (!isCancel) {
        isCancel = true
        target.removeEventListener(eventType, eventHandler)
      }

      register()

      Cancelable(() => unregister())
    }
  })

  def failed[S[_]: Source, A](source: S[A]): Observable[Throwable] = new Observable[Throwable] {
    def subscribe[G[_]: Sink](sink: G[_ >: Throwable]): Cancelable =
      Source[S].subscribe(source)(Observer.unsafeCreate[A](_ => (), Sink[G].onError(sink)(_)))
  }

  @inline def interval(delay: FiniteDuration): Observable[Long] = intervalMillis(delay.toMillis.toInt)

  def intervalMillis(delay: Int): Observable[Long] = new Observable[Long] {
    def subscribe[G[_]: Sink](sink: G[_ >: Long]): Cancelable = {
      var isCancel = false
      var counter: Long = 0

      def send(): Unit = {
        val current = counter
        counter += 1
        Sink[G].onNext(sink)(current)
      }

      send()

      val intervalId = dom.window.setInterval(() => if (!isCancel) send(), delay.toDouble)

      Cancelable { () =>
        isCancel = true
        dom.window.clearInterval(intervalId)
      }
    }
  }

  def concatAsync[F[_] : Effect, T](effects: F[T]*): Observable[T] = fromIterable(effects).concatMapAsync(identity)

  def concatSync[F[_] : RunSyncEffect, T](effects: F[T]*): Observable[T] = fromIterable(effects).mapSync(identity)

  def concatFuture[T](values: Future[T]*)(implicit ec: ExecutionContext): Observable[T] = fromIterable(values).concatMapFuture(identity)

  def concatAsync[F[_] : Effect, T, S[_] : Source](effect: F[T], source: S[T]): Observable[T] = new Observable[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Cancelable = {
      //TODO: proper cancel effects?
      var isCancel = false
      val consecutive = Cancelable.consecutive()

      consecutive += (() => Cancelable(() => isCancel = true))
      consecutive += (() => Source[S].subscribe(source)(sink))

      Effect[F].runAsync(effect)(either => IO {
        if (!isCancel) {
          either match {
            case Right(value) => Sink[G].onNext(sink)(value)
            case Left(error)  => Sink[G].onError(sink)(error)
          }
          consecutive.switch()
        }
      }).unsafeRunSync()

      consecutive
    }
  }

  def concatFuture[T, S[_] : Source](value: Future[T], source: S[T])(implicit ec: ExecutionContext): Observable[T] = concatAsync(IO.fromFuture(IO.pure(value))(IO.contextShift(ec)), source)

  def concatSync[F[_] : RunSyncEffect, T, S[_] : Source](effect: F[T], source: S[T]): Observable[T] = new Observable[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Cancelable = {
      recovered(Sink[G].onNext(sink)(RunSyncEffect[F].unsafeRun(effect)), Sink[G].onError(sink)(_))
      Source[S].subscribe(source)(sink)
    }
  }

  def merge[S[_]: Source, A](sources: S[A]*): Observable[A] = mergeSeq(sources)

  def mergeSeq[S[_]: Source, A](sources: Seq[S[A]]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      val subscriptions = sources.map { source =>
        Source[S].subscribe(source)(sink)
      }

      Cancelable.compositeFromIterable(subscriptions)
    }
  }

  def mergeVaried[SA[_]: Source, SB[_]: Source, A](sourceA: SA[A], sourceB: SB[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Cancelable.composite(
        Source[SA].subscribe(sourceA)(sink),
        Source[SB].subscribe(sourceB)(sink)
      )
    }
  }

  def switch[S[_]: Source, A](sources: S[A]*): Observable[A] = switchSeq(sources)

  def switchSeq[S[_]: Source, A](sources: Seq[S[A]]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      val variable = Cancelable.variable()
      sources.foreach { source =>
        variable() = Source[S].subscribe(source)(sink)
      }

      variable
    }
  }

  def switchVaried[SA[_]: Source, SB[_]: Source, A](sourceA: SA[A], sourceB: SB[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      val variable = Cancelable.variable()
      variable() = Source[SA].subscribe(sourceA)(sink)
      variable() = Source[SB].subscribe(sourceB)(sink)
      variable
    }
  }

  def map[F[_]: Source, A, B](source: F[A])(f: A => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(Observer.contramap(sink)(f))
  }

  def mapFilter[F[_]: Source, A, B](source: F[A])(f: A => Option[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(Observer.contramapFilter(sink)(f))
  }

  def collect[F[_]: Source, A, B](source: F[A])(f: PartialFunction[A, B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(Observer.contracollect(sink)(f))
  }

  def filter[F[_]: Source, A](source: F[A])(f: A => Boolean): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = Source[F].subscribe(source)(Observer.contrafilter[G, A](sink)(f))
  }

  def scan0[F[_]: Source, A, B](source: F[A])(seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      Sink[G].onNext(sink)(seed)
      Source[F].subscribe(source)(Observer.contrascan[G, B, A](sink)(seed)(f))
    }
  }

  def scan[F[_]: Source, A, B](source: F[A])(seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(Observer.contrascan[G, B, A](sink)(seed)(f))
  }

  def mapEither[F[_]: Source, A, B](source: F[A])(f: A => Either[Throwable, B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(Observer.contramapEither(sink)(f))
  }

  def recoverToEither[F[_]: Source, A](source: F[A]): Observable[Either[Throwable, A]] = Observable.map[F, A, Either[Throwable, A]](source)(Right(_)).recover { case err => Left(err) }

  def recover[F[_]: Source, A](source: F[A])(f: PartialFunction[Throwable, A]): Observable[A] = recoverOption(source)(f andThen (Some(_)))

  def recoverOption[F[_]: Source, A](source: F[A])(f: PartialFunction[Throwable, Option[A]]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Source[F].subscribe(source)(Observer.doOnError(sink) { error =>
        f.lift(error) match {
          case Some(v) => v.foreach(Sink[G].onNext(sink)(_))
          case None => Sink[G].onError(sink)(error)
        }
      })
    }
  }

  def doOnNext[F[_]: Source, A](source: F[A])(f: A => Unit): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Source[F].subscribe(source)(Observer.doOnNext[G, A](sink) { value =>
        f(value)
        Sink[G].onNext(sink)(value)
      })
    }
  }

  def doOnError[F[_]: Source, A](source: F[A])(f: Throwable => Unit): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Source[F].subscribe(source)(Observer.doOnError(sink) { error =>
        f(error)
        Sink[G].onError(sink)(error)
      })
    }
  }

  def mergeMap[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(f: A => SB[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      val builder = Cancelable.builder()

      val subscription = Source[SA].subscribe(sourceA)(Observer.create[A](
        { value =>
          val sourceB = f(value)
          builder += Source[SB].subscribe(sourceB)(sink)
        },
        Sink[G].onError(sink),
      ))

      Cancelable.composite(subscription, builder)
    }
  }

  def switchMap[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(f: A => SB[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      val current = Cancelable.variable()

      val subscription = Source[SA].subscribe(sourceA)(Observer.create[A](
        { value =>
          val sourceB = f(value)
          current() = Source[SB].subscribe(sourceB)(sink)
        },
        Sink[G].onError(sink),
      ))

      Cancelable.composite(current, subscription)
    }
  }

  def concatMapAsync[S[_]: Source, F[_]: Effect, A, B](sourceA: S[A])(f: A => F[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      val consecutive = Cancelable.consecutive()

      val subscription = Source[S].subscribe(sourceA)(Observer.create[A](
        { value =>
          val effect = f(value)
          consecutive += { () =>
            //TODO: proper cancel effects?
            var isCancel = false
            Effect[F].runAsync(effect)(either => IO {
              if (!isCancel) {
                either match {
                  case Right(value) => Sink[G].onNext(sink)(value)
                  case Left(error)  => Sink[G].onError(sink)(error)
                }
                consecutive.switch()
              }
            }).unsafeRunSync()

            Cancelable(() => isCancel = true)
          }
        },
        Sink[G].onError(sink),
      ))

      Cancelable.composite(subscription, consecutive)
    }
  }

  @inline def concatMapFuture[S[_]: Source, A, B](source: S[A])(f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] = concatMapAsync(source)(v => IO.fromFuture(IO.pure(f(v)))(IO.contextShift(ec)))

  @inline def mapSync[S[_]: Source, F[_]: RunSyncEffect, A, B](source: S[A])(f: A => F[B]): Observable[B] = map(source)(v => RunSyncEffect[F].unsafeRun(f(v)))

  @inline def zip[SA[_]: Source, SB[_]: Source, A, B, R](sourceA: SA[A], sourceB: SB[B]): Observable[(A,B)] =
    zipMap(sourceA, sourceB)(_ -> _)
  @inline def zip[SA[_]: Source, SB[_]: Source, A, B, C](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C]): Observable[(A,B,C)] =
    zipMap(sourceA, sourceB, sourceC)((a,b,c) => (a,b,c))
  @inline def zip[SA[_]: Source, SB[_]: Source, A, B, C, D](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D]): Observable[(A,B,C,D)] =
    zipMap(sourceA, sourceB, sourceC, sourceD)((a,b,c,d) => (a,b,c,d))
  @inline def zip[SA[_]: Source, SB[_]: Source, A, B, C, D, E](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E]): Observable[(A,B,C,D,E)] =
    zipMap(sourceA, sourceB, sourceC, sourceD, sourceE)((a,b,c,d,e) => (a,b,c,d,e))
  @inline def zip[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E], sourceF: SB[F]): Observable[(A,B,C,D,E,F)] =
    zipMap(sourceA, sourceB, sourceC, sourceD, sourceE, sourceF)((a,b,c,d,e,f) => (a,b,c,d,e,f))

  def zipMap[SA[_]: Source, SB[_]: Source, A, B, R](sourceA: SA[A], sourceB: SB[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
    def subscribe[G[_]: Sink](sink: G[_ >: R]): Cancelable = {
      val seqA = new js.Array[A]
      val seqB = new js.Array[B]

      def send(): Unit = if (seqA.nonEmpty && seqB.nonEmpty) {
        val a = seqA.splice(0, deleteCount = 1)(0)
        val b = seqB.splice(0, deleteCount = 1)(0)
        Sink[G].onNext(sink)(f(a,b))
      }

      Cancelable.composite(
        Source[SA].subscribe(sourceA)(Observer.create[A](
          { value =>
            seqA.push(value)
            send()
          },
          Sink[G].onError(sink),
        )),
        Source[SB].subscribe(sourceB)(Observer.create[B](
          { value =>
            seqB.push(value)
            send()
          },
          Sink[G].onError(sink),
        ))
      )
    }
  }

  def zipMap[SA[_]: Source, SB[_]: Source, A, B, C, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C])(f: (A, B, C) => R): Observable[R] =
    zipMap(sourceA, zip(sourceB, sourceC))((a, tail) => f(a, tail._1, tail._2))
  def zipMap[SA[_]: Source, SB[_]: Source, A, B, C, D, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D])(f: (A, B, C, D) => R): Observable[R] =
    zipMap(sourceA, zip(sourceB, sourceC, sourceD))((a, tail) => f(a, tail._1, tail._2, tail._3))
  def zipMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E])(f: (A, B, C, D, E) => R): Observable[R] =
    zipMap(sourceA, zip(sourceB, sourceC, sourceD, sourceE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
  def zipMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E], sourceF: SB[F])(f: (A, B, C, D, E, F) => R): Observable[R] =
    zipMap(sourceA, zip(sourceB, sourceC, sourceD, sourceE, sourceF))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4, tail._5))

  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A], sourceB: SB[B]): Observable[(A,B)] =
    combineLatestMap(sourceA, sourceB)(_ -> _)
  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B, C](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C]): Observable[(A,B,C)] =
    combineLatestMap(sourceA, sourceB, sourceC)((a,b,c) => (a,b,c))
  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B, C, D](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D]): Observable[(A,B,C,D)] =
    combineLatestMap(sourceA, sourceB, sourceC, sourceD)((a,b,c,d) => (a,b,c,d))
  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B, C, D, E](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E]): Observable[(A,B,C,D,E)] =
    combineLatestMap(sourceA, sourceB, sourceC, sourceD, sourceE)((a,b,c,d,e) => (a,b,c,d,e))
  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E], sourceF: SB[F]): Observable[(A,B,C,D,E,F)] =
    combineLatestMap(sourceA, sourceB, sourceC, sourceD, sourceE, sourceF)((a,b,c,d,e,f) => (a,b,c,d,e,f))

  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, R](sourceA: SA[A], sourceB: SB[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
    def subscribe[G[_]: Sink](sink: G[_ >: R]): Cancelable = {
      var latestA: Option[A] = None
      var latestB: Option[B] = None

      def send(): Unit = for {
        a <- latestA
        b <- latestB
      } Sink[G].onNext(sink)(f(a,b))

      Cancelable.composite(
        Source[SA].subscribe(sourceA)(Observer.create[A](
          { value =>
            latestA = Some(value)
            send()
          },
          Sink[G].onError(sink),
        )),
        Source[SB].subscribe(sourceB)(Observer.create[B](
          { value =>
            latestB = Some(value)
            send()
          },
          Sink[G].onError(sink),
        ))
      )
    }
  }

  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C])(f: (A, B, C) => R): Observable[R] =
    combineLatestMap(sourceA, combineLatest(sourceB, sourceC))((a, tail) => f(a, tail._1, tail._2))
  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D])(f: (A, B, C, D) => R): Observable[R] =
    combineLatestMap(sourceA, combineLatest(sourceB, sourceC, sourceD))((a, tail) => f(a, tail._1, tail._2, tail._3))
  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E])(f: (A, B, C, D, E) => R): Observable[R] =
    combineLatestMap(sourceA, combineLatest(sourceB, sourceC, sourceD, sourceE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F, R](sourceA: SA[A], sourceB: SB[B], sourceC: SB[C], sourceD: SB[D], sourceE: SB[E], sourceF: SB[F])(f: (A, B, C, D, E, F) => R): Observable[R] =
    combineLatestMap(sourceA, combineLatest(sourceB, sourceC, sourceD, sourceE, sourceF))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4, tail._5))

  def withLatest[SA[_]: Source, SB[_]: Source, A, B](source: SA[A], latest: SB[B]): Observable[(A,B)] =
    withLatestMap(source, latest)(_ -> _)
  def withLatest[SA[_]: Source, SB[_]: Source, A, B, C](source: SA[A], latestB: SB[B], latestC: SB[C]): Observable[(A,B,C)] =
    withLatestMap(source, latestB, latestC)((a,b,c) => (a,b,c))
  def withLatest[SA[_]: Source, SB[_]: Source, A, B, C, D](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D]): Observable[(A,B,C,D)] =
    withLatestMap(source, latestB, latestC, latestD)((a,b,c,d) => (a,b,c,d))
  def withLatest[SA[_]: Source, SB[_]: Source, A, B, C, D, E](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D], latestE: SB[E]): Observable[(A,B,C,D,E)] =
    withLatestMap(source, latestB, latestC, latestD, latestE)((a,b,c,d,e) => (a,b,c,d,e))
  def withLatest[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D], latestE: SB[E], latestF: SB[F]): Observable[(A,B,C,D,E,F)] =
    withLatestMap(source, latestB, latestC, latestD, latestE, latestF)((a,b,c,d,e,f) => (a,b,c,d,e,f))

  def withLatestMap[SA[_]: Source, SB[_]: Source, A, B, R](source: SA[A], latest: SB[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
    def subscribe[G[_]: Sink](sink: G[_ >: R]): Cancelable = {
      var latestValue: Option[B] = None

      Cancelable.composite(
        Source[SB].subscribe(latest)(Observer.unsafeCreate[B](
          value => latestValue = Some(value),
          Sink[G].onError(sink),
        )),
        Source[SA].subscribe(source)(Observer.create[A](
          value => latestValue.foreach(latestValue => Sink[G].onNext(sink)(f(value, latestValue))),
          Sink[G].onError(sink),
        ))
      )
    }
  }

  def withLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, R](source: SA[A], latestB: SB[B], latestC: SB[C])(f: (A, B, C) => R): Observable[R] =
    withLatestMap(source, combineLatest(latestB, latestC))((a, tail) => f(a, tail._1, tail._2))
  def withLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, R](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D])(f: (A, B, C, D) => R): Observable[R] =
    withLatestMap(source, combineLatest(latestB, latestC, latestD))((a, tail) => f(a, tail._1, tail._2, tail._3))
  def withLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, R](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D], latestE: SB[E])(f: (A, B, C, D, E) => R): Observable[R] =
    withLatestMap(source, combineLatest(latestB, latestC, latestD, latestE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
  def withLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F, R](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D], latestE: SB[E], latestF: SB[F])(f: (A, B, C, D, E, F) => R): Observable[R] =
    withLatestMap(source, combineLatest(latestB, latestC, latestD, latestE, latestF))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4, tail._5))
  def withLatestMap[SA[_]: Source, SB[_]: Source, A, B, C, D, E, F, G, R](source: SA[A], latestB: SB[B], latestC: SB[C], latestD: SB[D], latestE: SB[E], latestF: SB[F], latestG: SB[G])(f: (A, B, C, D, E, F, G) => R): Observable[R] =
    withLatestMap(source, combineLatest(latestB, latestC, latestD, latestE, latestF, latestG))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4, tail._5, tail._6))

  def zipWithIndex[S[_]: Source, A, R](source: S[A]): Observable[(A, Int)] = new Observable[(A, Int)] {
    def subscribe[G[_]: Sink](sink: G[_ >: (A, Int)]): Cancelable = {
      var counter = 0

      Source[S].subscribe(source)(Observer.unsafeCreate[A](
        { value =>
          val index = counter
          counter += 1
          Sink[G].onNext(sink)((value, index))
        },
        Sink[G].onError(sink),
      ))
    }
  }

  @inline def debounce[S[_]: Source, A](source: S[A])(duration: FiniteDuration): Observable[A] = debounceMillis(source)(duration.toMillis.toInt)

  def debounceMillis[S[_]: Source, A](source: S[A])(duration: Int): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var lastTimeout: js.UndefOr[Int] = js.undefined
      var isCancel = false

      Cancelable.composite(
        Cancelable { () =>
          isCancel = true
          lastTimeout.foreach(dom.window.clearTimeout)
        },
        Source[S].subscribe(source)(Observer.create[A](
          { value =>
            lastTimeout.foreach { id =>
              dom.window.clearTimeout(id)
            }
            lastTimeout = dom.window.setTimeout(
              () =>  if (!isCancel) Sink[G].onNext(sink)(value),
              duration.toDouble
            )
          },
          Sink[G].onError(sink),
        ))
      )
    }
  }

  @inline def sample[S[_]: Source, A](source: S[A])(duration: FiniteDuration): Observable[A] = sampleMillis(source)(duration.toMillis.toInt)

  def sampleMillis[S[_]: Source, A](source: S[A])(duration: Int): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var isCancel = false
      var lastValue: Option[A] = None

      def send(): Unit = {
        lastValue.foreach(Sink[G].onNext(sink))
        lastValue = None
      }

      val intervalId = dom.window.setInterval(() => if (!isCancel) send(), duration.toDouble)

      Cancelable.composite(
        Cancelable { () =>
          isCancel = true
          dom.window.clearInterval(intervalId)
        },
        Source[S].subscribe(source)(Observer.unsafeCreate[A](
          value => lastValue = Some(value),
          Sink[G].onError(sink),
        ))
      )
    }
  }

  //TODO setImmediate?
  @inline def async[S[_]: Source, A](source: S[A]): Observable[A] = delayMillis(source)(0)

  @inline def delay[S[_]: Source, A](source: S[A])(duration: FiniteDuration): Observable[A] = delayMillis(source)(duration.toMillis.toInt)

  def delayMillis[S[_]: Source, A](source: S[A])(duration: Int): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var lastTimeout: js.UndefOr[Int] = js.undefined
      var isCancel = false

      // TODO: we onyl actually cancel the last timeout. The check isCancel
      // makes sure that cancelled subscription is really respected.
      Cancelable.composite(
        Cancelable { () =>
          isCancel = true
          lastTimeout.foreach(dom.window.clearTimeout)
        },
        Source[S].subscribe(source)(Observer.create[A](
          { value =>
            lastTimeout = dom.window.setTimeout(
              () => if (!isCancel) Sink[G].onNext(sink)(value),
              duration.toDouble
            )
          },
          Sink[G].onError(sink),
        ))
      )
    }
  }

  def distinct[S[_]: Source, A : Eq](source: S[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var lastValue: Option[A] = None

      Source[S].subscribe(source)(Observer.unsafeCreate[A](
        { value =>
            val shouldSend = lastValue.forall(lastValue => !Eq[A].eqv(lastValue, value))
            if (shouldSend) {
              lastValue = Some(value)
              Sink[G].onNext(sink)(value)
            }
        },
        Sink[G].onError(sink),
      ))
    }
  }

  @inline def distinctOnEquals[S[_]: Source, A](source: S[A]): Observable[A] = distinct(source)(Source[S], Eq.fromUniversalEquals)

  def withDefaultSubscription[S[_]: Source, F[_]: Sink, A](source: S[A])(sink: F[A]): Observable[A] = new Observable[A] {
    private var defaultSubscription = Source[S].subscribe(source)(sink)

    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      // stop the default subscription.
      if (defaultSubscription != null) {
        defaultSubscription.cancel()
        defaultSubscription = null
      }

      Source[S].subscribe(source)(sink)
    }
  }

  @inline def transformSource[F[_]: Source, FF[_]: Source, A, B](source: F[A])(transform: F[A] => FF[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[FF].subscribe(transform(source))(sink)
  }

  @inline def transformSink[F[_]: Source, G[_]: Sink, A, B](source: F[A])(transform: Observer[_ >: B] => G[A]): Observable[B] = new Observable[B] {
    def subscribe[GG[_]: Sink](sink: GG[_ >: B]): Cancelable = Source[F].subscribe(source)(transform(Observer.lift(sink)))
  }

  @inline def share[F[_]: Source, A](source: F[A]): Observable[A] = publish(source).refCount

  @inline def publish[F[_]: Source, A](source: F[A]): Observable.Connectable[A] = multicast(source)(Subject.publish[A])
  @inline def replay[F[_]: Source, A](source: F[A]): Observable.ConnectableMaybeValue[A] = multicastMaybeValue(source)(Subject.replay[A])
  @inline def behavior[F[_]: Source, A](source: F[A])(value: A): Observable.ConnectableValue[A] = multicastValue(source)(Subject.behavior[A](value))

  @inline def publishSelector[F[_]: Source, A, B](source: F[A])(f: Observable[A] => Observable[B]): Observable[B] = transformSource(source)(s => f(publish(s).refCount))
  @inline def replaySelector[F[_]: Source, A, B](source: F[A])(f: Observable.MaybeValue[A] => Observable[B]): Observable[B] = transformSource(source)(s => f(replay(s).refCount))
  @inline def behaviorSelector[F[_]: Source, A, B](source: F[A])(value: A)(f: Observable.Value[A] => Observable[B]): Observable[B] = transformSource(source)(s => f(behavior(s)(value).refCount))

  def multicast[F[_]: Source, A, S[_] : Source : Sink](source: F[A])(pipe: S[A]): Connectable[A] = new Connectable[A] {
    private val refCount: Cancelable.RefCount = Cancelable.refCount(() => Source[F].subscribe(source)(pipe))
    def connect(): Cancelable = refCount.ref()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = Source[S].subscribe(pipe)(sink)
  }

  def multicastValue[F[_]: Source, A](source: F[A])(pipe: Subject.Value[A]): ConnectableValue[A] = new Connectable[A] with Value[A] {
    private val refCount: Cancelable.RefCount = Cancelable.refCount(() => Source[F].subscribe(source)(pipe))
    def now(): A = pipe.now()
    def connect(): Cancelable = refCount.ref()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = pipe.subscribe(sink)
  }

  def multicastMaybeValue[F[_]: Source, A](source: F[A])(pipe: Subject.MaybeValue[A]): ConnectableMaybeValue[A] = new Connectable[A] with MaybeValue[A] {
    private val refCount: Cancelable.RefCount = Cancelable.refCount(() => Source[F].subscribe(source)(pipe))
    def now(): Option[A] = pipe.now()
    def connect(): Cancelable = refCount.ref()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = pipe.subscribe(sink)
  }

  def refCount[A](source: Connectable[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable =
      Cancelable.composite(source.subscribe(sink), source.connect())
  }
  def refCountValue[A](source: ConnectableValue[A]): Observable.Value[A] = new Value[A] {
    def now() = source.now()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable =
      Cancelable.composite(source.subscribe(sink), source.connect())
  }
  def refCountMaybeValue[A](source: ConnectableMaybeValue[A]): Observable.MaybeValue[A] = new Observable.MaybeValue[A] {
    def now() = source.now()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable =
      Cancelable.composite(source.subscribe(sink), source.connect())
  }

  @inline def prependSync[S[_]: Source, A, F[_] : RunSyncEffect](source: S[A])(value: F[A]): Observable[A] = concatSync[F, A, S](value, source)
  @inline def prependAsync[S[_]: Source, A, F[_] : Effect](source: S[A])(value: F[A]): Observable[A] = concatAsync[F, A, S](value, source)
  @inline def prependFuture[S[_]: Source, A](source: S[A])(value: Future[A])(implicit ec: ExecutionContext): Observable[A] = concatFuture[A, S](value, source)

  def prepend[F[_]: Source, A](source: F[A])(value: A): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Sink[G].onNext(sink)(value)
      Source[F].subscribe(source)(sink)
    }
  }

  def startWith[F[_]: Source, A](source: F[A])(values: Iterable[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      values.foreach(Sink[G].onNext(sink))
      Source[F].subscribe(source)(sink)
    }
  }

  @inline def head[F[_]: Source, A](source: F[A]): Observable[A] = take(source)(1)

  def take[F[_]: Source, A](source: F[A])(num: Int): Observable[A] = {
    if (num <= 0) Observable.empty
    else new Observable[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
        var counter = 0
        val subscription = Cancelable.variable()
        subscription() = Source[F].subscribe(source)(Observer.contrafilter(sink) { _ =>
          if (num > counter) {
            counter += 1
            true
          } else {
            subscription.cancel()
            false
          }
        })

        subscription
      }
    }
  }

  def takeWhile[F[_]: Source, A](source: F[A])(predicate: A => Boolean): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var finishedTake = false
      val subscription = Cancelable.variable()
      subscription() = Source[F].subscribe(source)(Observer.contrafilter[G, A](sink) { v =>
        if (finishedTake) false
        else if (predicate(v)) true
        else {
          finishedTake = true
          subscription.cancel()
          false
        }
      })

      subscription
    }
  }

  def takeUntil[F[_]: Source, FU[_]: Source, A](source: F[A])(until: FU[Unit]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var finishedTake = false
      val subscription = Cancelable.builder()

      subscription += Source[FU].subscribe(until)(Observer.unsafeCreate[Unit](
        { _ =>
          finishedTake = true
          subscription.cancel()
        },
        Sink[G].onError(sink)(_)
      ))

      if (!finishedTake) subscription += Source[F].subscribe(source)(Observer.contrafilter[G, A](sink)(_ => !finishedTake))

      subscription
    }
  }

  def drop[F[_]: Source, A](source: F[A])(num: Int): Observable[A] = {
    if (num <= 0) Observable.lift(source)
    else new Observable[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
        var counter = 0
        Source[F].subscribe(source)(Observer.contrafilter(sink) { _ =>
          if (num > counter) {
            counter += 1
            false
          } else true
        })
      }
    }
  }

  def dropWhile[F[_]: Source, A](source: F[A])(predicate: A => Boolean): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var finishedDrop = false
      Source[F].subscribe(source)(Observer.contrafilter[G, A](sink) { v =>
        if (finishedDrop) true
        else if (predicate(v)) false
        else {
          finishedDrop = true
          true
        }
      })
    }
  }

  def dropUntil[F[_]: Source, FU[_]: Source, A](source: F[A])(until: FU[Unit]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var finishedDrop = false

      val untilCancelable = Cancelable.variable()
      untilCancelable() = Source[FU].subscribe(until)(Observer.unsafeCreate[Unit](
        { _ =>
          finishedDrop = true
          untilCancelable.cancel()
        },
        Sink[G].onError(sink)(_)
      ))

      val subscription = Source[F].subscribe(source)(Observer.contrafilter[G, A](sink)(_ => finishedDrop))

      Cancelable.composite(subscription, untilCancelable)
    }
  }

  @inline implicit class Operations[A](val source: Observable[A]) extends AnyVal {
    @inline def liftSource[G[_]: LiftSource]: G[A] = LiftSource[G].lift(source)
    @inline def failed: Observable[Throwable] = Observable.failed(source)
    @inline def mergeMap[S[_]: Source, B](f: A => S[B]): Observable[B] = Observable.mergeMap(source)(f)
    @inline def switchMap[S[_]: Source, B](f: A => S[B]): Observable[B] = Observable.switchMap(source)(f)
    @inline def zip[S[_]: Source, B](combined: S[B]): Observable[(A,B)] = Observable.zip(source, combined)
    @inline def zipMap[S[_]: Source, B, R](combined: S[B])(f: (A, B) => R): Observable[R] = Observable.zipMap(source, combined)(f)
    @inline def combineLatest[S[_]: Source, B](combined: S[B]): Observable[(A,B)] = Observable.combineLatest(source, combined)
    @inline def combineLatestMap[S[_]: Source, B, R](combined: S[B])(f: (A, B) => R): Observable[R] = Observable.combineLatestMap(source, combined)(f)
    @inline def withLatest[S[_]: Source, B](latest: S[B]): Observable[(A,B)] = Observable.withLatest(source, latest)
    @inline def withLatestMap[S[_]: Source, B, R](latest: S[B])(f: (A, B) => R): Observable[R] = Observable.withLatestMap(source, latest)(f)
    @inline def zipWithIndex: Observable[(A, Int)] = Observable.zipWithIndex(source)
    @inline def debounce(duration: FiniteDuration): Observable[A] = Observable.debounce(source)(duration)
    @inline def debounceMillis(millis: Int): Observable[A] = Observable.debounceMillis(source)(millis)
    @inline def sample(duration: FiniteDuration): Observable[A] = Observable.sample(source)(duration)
    @inline def sampleMillis(millis: Int): Observable[A] = Observable.sampleMillis(source)(millis)
    @inline def async: Observable[A] = Observable.async(source)
    @inline def delay(duration: FiniteDuration): Observable[A] = Observable.delay(source)(duration)
    @inline def delayMillis(millis: Int): Observable[A] = Observable.delayMillis(source)(millis)
    @inline def distinctOnEquals: Observable[A] = Observable.distinctOnEquals(source)
    @inline def distinct(implicit eq: Eq[A]): Observable[A] = Observable.distinct(source)
    @inline def concatMapFuture[B](f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] = Observable.concatMapFuture(source)(f)
    @inline def concatMapAsync[G[_]: Effect, B](f: A => G[B]): Observable[B] = Observable.concatMapAsync(source)(f)
    @inline def mapSync[G[_]: RunSyncEffect, B](f: A => G[B]): Observable[B] = Observable.mapSync(source)(f)
    @inline def map[B](f: A => B): Observable[B] = Observable.map(source)(f)
    @inline def mapEither[B](f: A => Either[Throwable, B]): Observable[B] = Observable.mapEither(source)(f)
    @inline def mapFilter[B](f: A => Option[B]): Observable[B] = Observable.mapFilter(source)(f)
    @inline def doOnNext(f: A => Unit): Observable[A] = Observable.doOnNext(source)(f)
    @inline def doOnError(f: Throwable => Unit): Observable[A] = Observable.doOnError(source)(f)
    @inline def collect[B](f: PartialFunction[A, B]): Observable[B] = Observable.collect(source)(f)
    @inline def filter(f: A => Boolean): Observable[A] = Observable.filter(source)(f)
    @inline def scan[B](seed: B)(f: (B, A) => B): Observable[B] = Observable.scan(source)(seed)(f)
    @inline def scan0[B](seed: B)(f: (B, A) => B): Observable[B] = Observable.scan0(source)(seed)(f)
    @inline def recover(f: PartialFunction[Throwable, A]): Observable[A] = Observable.recover(source)(f)
    @inline def recoverOption(f: PartialFunction[Throwable, Option[A]]): Observable[A] = Observable.recoverOption(source)(f)
    @inline def publish: Observable.Connectable[A] = Observable.publish(source)
    @inline def replay: Observable.ConnectableMaybeValue[A] = Observable.replay(source)
    @inline def behavior(value: A): Observable.ConnectableValue[A] = Observable.behavior(source)(value)
    @inline def publishSelector[B](f: Observable[A] => Observable[B]): Observable[B] = Observable.publishSelector(source)(f)
    @inline def replaySelector[B](f: Observable.MaybeValue[A] => Observable[B]): Observable[B] = Observable.replaySelector(source)(f)
    @inline def behaviorSelector[B](value: A)(f: Observable.Value[A] => Observable[B]): Observable[B] = Observable.behaviorSelector(source)(value)(f)
    @inline def transformSource[S[_]: Source, B](transform: Observable[A] => S[B]): Observable[B] = Observable.transformSource(source)(transform)
    @inline def transformSink[G[_]: Sink, B](transform: Observer[_ >: B] => G[A]): Observable[B] = Observable.transformSink[Observable, G, A, B](source)(transform)
    @inline def prepend(value: A): Observable[A] = Observable.prepend(source)(value)
    @inline def prependSync[F[_] : RunSyncEffect](value: F[A]): Observable[A] = Observable.prependSync(source)(value)
    @inline def prependAsync[F[_] : Effect](value: F[A]): Observable[A] = Observable.prependAsync(source)(value)
    @inline def prependFuture(value: Future[A])(implicit ec: ExecutionContext): Observable[A] = Observable.prependFuture(source)(value)
    @inline def startWith(values: Iterable[A]): Observable[A] = Observable.startWith(source)(values)
    @inline def head: Observable[A] = Observable.head(source)
    @inline def take(num: Int): Observable[A] = Observable.take(source)(num)
    @inline def takeWhile(predicate: A => Boolean): Observable[A] = Observable.takeWhile(source)(predicate)
    @inline def takeUntil[F[_]: Source](until: F[Unit]): Observable[A] = Observable.takeUntil(source)(until)
    @inline def drop(num: Int): Observable[A] = Observable.drop(source)(num)
    @inline def dropWhile(predicate: A => Boolean): Observable[A] = Observable.dropWhile(source)(predicate)
    @inline def dropUntil[F[_]: Source](until: F[Unit]): Observable[A] = Observable.dropUntil(source)(until)
    @inline def withDefaultSubscription[G[_] : Sink](sink: G[A]): Observable[A] = Observable.withDefaultSubscription(source)(sink)
    @inline def subscribe(): Cancelable = source.subscribe(Observer.empty)
    @inline def foreach(f: A => Unit): Cancelable = source.subscribe(Observer.create(f))
  }

  @inline implicit class ConnectableOperations[A](val source: Observable.Connectable[A]) extends AnyVal {
    @inline def refCount: Observable[A] = Observable.refCount(source)
  }
  @inline implicit class ConnectableValueOperations[A](val source: Observable.ConnectableValue[A]) extends AnyVal {
    @inline def refCount: Observable.Value[A] = Observable.refCountValue(source)
  }
  @inline implicit class ConnectableMaybeValueOperations[A](val source: Observable.ConnectableMaybeValue[A]) extends AnyVal {
    @inline def refCount: Observable.MaybeValue[A] = Observable.refCountMaybeValue(source)
  }

  @inline implicit class SyncEventOperations[EV <: dom.Event](val source: Synchronous[EV]) extends AnyVal {
    @inline private def withOperator(newOperator: EV => Unit): Synchronous[EV] = new Synchronous(source.map { ev => newOperator(ev); ev })

    @inline def preventDefault: Synchronous[EV] = withOperator(_.preventDefault)
    @inline def stopPropagation: Synchronous[EV] = withOperator(_.stopPropagation)
    @inline def stopImmediatePropagation: Synchronous[EV] = withOperator(_.stopImmediatePropagation)
  }

  @inline implicit class SubjectValueOperations[A](val handler: Subject.Value[A]) extends AnyVal {
    def lens[B](read: A => B)(write: (A, B) => A): Subject.Value[B] = new Observer[B] with Observable.Value[B] {
      @inline def now() = read(handler.now())
      @inline def onNext(value: B): Unit = handler.onNext(write(handler.now(), value))
      @inline def onError(error: Throwable): Unit = handler.onError(error)
      @inline def subscribe[G[_] : Sink](sink: G[_ >: B]): Cancelable = handler.map(read).subscribe(sink)
    }
  }

  @inline implicit class SubjectMaybeValueOperations[A](val handler: Subject.MaybeValue[A]) extends AnyVal {
    def lens[B](seed: => A)(read: A => B)(write: (A, B) => A): Subject.MaybeValue[B] = new Observer[B] with Observable.MaybeValue[B] {
      @inline def now() = handler.now().map(read)
      @inline def onNext(value: B): Unit = handler.onNext(write(handler.now().getOrElse(seed), value))
      @inline def onError(error: Throwable): Unit = handler.onError(error)
      @inline def subscribe[G[_] : Sink](sink: G[_ >: B]): Cancelable = handler.map(read).subscribe(sink)
    }
  }

  @inline implicit class SubjectOperations[I,O](val handler: ProSubject[I,O]) extends AnyVal {
    @inline def transformSubjectSource[S[_] : Source, O2](g: Observable[O] => S[O2]): ProSubject[I, O2] = ProSubject.from[Observer, S, I, O2](handler, g(handler))
    @inline def transformSubjectSink[G[_] : Sink, I2](f: Observer[I] => G[I2]): ProSubject[I2, O] = ProSubject.from[G, Observable, I2, O](f(handler), handler)
    @inline def transformSubject[G[_] : Sink, S[_] : Source, I2, O2](f: Observer[I] => G[I2])(g: Observable[O] => S[O2]): ProSubject[I2, O2] = ProSubject.from(f(handler), g(handler))
  }

  private def recovered[T](action: => Unit, onError: Throwable => Unit) = try action catch { case NonFatal(t) => onError(t) }
}
