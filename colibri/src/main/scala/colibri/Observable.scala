package colibri

import colibri.effect._

import cats.{ MonoidK, Applicative, FunctorFilter, Eq, Semigroupal }
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
    @inline def lift[H[_] : Source, A](source: H[A]): Observable[A] = Observable.lift[H, A](source)
  }

  implicit object monoidK extends MonoidK[Observable] {
    @inline def empty[T] = Observable.empty
    @inline def combineK[T](a: Observable[T], b: Observable[T]) = Observable.mergeVaried(a, b)
  }

  implicit object applicative extends Applicative[Observable] {
    @inline def ap[A, B](ff: Observable[A => B])(fa: Observable[A]): Observable[B] = Observable.combineLatestMap(ff, fa)((f, a) => f(a))
    @inline def pure[A](a: A): Observable[A] = Observable(a)
    @inline override def map[A, B](fa: Observable[A])(f: A => B): Observable[B] = Observable.map(fa)(f)
  }

  implicit object functorFilter extends FunctorFilter[Observable] {
    @inline def functor = Observable.applicative
    @inline def mapFilter[A, B](fa: Observable[A])(f: A => Option[B]): Observable[B] = Observable.mapFilter(fa)(f)
  }

  implicit object semigroupal extends Semigroupal[Observable] {
    @inline def product[A, B](fa: Observable[A], fb: Observable[B]): Observable[(A,B)] = Observable.combineLatest(fa, fb)
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

  def lift[H[_] : Source, A](source: H[A]): Observable[A] = source match {
    case source: Observable[A@unchecked] => source
    case _ => new Observable[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = Source[H].subscribe(source)(sink)
    }
  }

  @inline def create[A](produce: Observer[A] => Cancelable): Observable[A] = createLift[Observer, A](produce)

  def createLift[F[_]: LiftSink, A](produce: F[_ >: A] => Cancelable): Observable[A] = new Observable[A] {
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

  def failed[H[_] : Source, A](source: H[A]): Observable[Throwable] = new Observable[Throwable] {
    def subscribe[G[_]: Sink](sink: G[_ >: Throwable]): Cancelable =
      Source[H].subscribe(source)(Observer.unsafeCreate[A](_ => (), Sink[G].onError(sink)(_)))
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

  def via[H[_] : Source, G[_]: Sink, A](source: H[A])(sink: G[A]): Observable[A] = new Observable[A] {
    def subscribe[GG[_]: Sink](sink2: GG[_ >: A]): Cancelable = Source[H].subscribe(source)(Observer.combine[Observer, A](Observer.lift(sink), Observer.lift(sink2)))
  }

  def concatAsync[F[_] : Effect, T](effects: F[T]*): Observable[T] = fromIterable(effects).mapAsync(identity)

  def concatSync[F[_] : RunSyncEffect, T](effects: F[T]*): Observable[T] = fromIterable(effects).mapSync(identity)

  def concatFuture[T](values: Future[T]*)(implicit ec: ExecutionContext): Observable[T] = fromIterable(values).mapFuture(identity)

  def concatAsync[F[_] : Effect, T, H[_] : Source](effect: F[T], source: H[T]): Observable[T] = new Observable[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Cancelable = {
      //TODO: proper cancel effects?
      var isCancel = false
      val consecutive = Cancelable.consecutive()

      consecutive += (() => Cancelable(() => isCancel = true))
      consecutive += (() => Source[H].subscribe(source)(sink))

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

  def concatFuture[T, H[_] : Source](value: Future[T], source: H[T])(implicit ec: ExecutionContext): Observable[T] = concatAsync(IO.fromFuture(IO.pure(value))(IO.contextShift(ec)), source)

  def concatSync[F[_] : RunSyncEffect, T, H[_] : Source](effect: F[T], source: H[T]): Observable[T] = new Observable[T] {
    def subscribe[G[_]: Sink](sink: G[_ >: T]): Cancelable = {
      recovered(Sink[G].onNext(sink)(RunSyncEffect[F].unsafeRun(effect)), Sink[G].onError(sink)(_))
      Source[H].subscribe(source)(sink)
    }
  }

  def merge[H[_] : Source, A](sources: H[A]*): Observable[A] = mergeSeq(sources)

  def mergeSeq[H[_] : Source, A](sources: Seq[H[A]]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      val subscriptions = sources.map { source =>
        Source[H].subscribe(source)(sink)
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

  def switch[H[_] : Source, A](sources: H[A]*): Observable[A] = switchSeq(sources)

  def switchSeq[H[_] : Source, A](sources: Seq[H[A]]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      val variable = Cancelable.variable()
      sources.foreach { source =>
        variable() = Source[H].subscribe(source)(sink)
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

  def map[H[_] : Source, A, B](source: H[A])(f: A => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[H].subscribe(source)(Observer.contramap(sink)(f))
  }

  def mapFilter[H[_] : Source, A, B](source: H[A])(f: A => Option[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[H].subscribe(source)(Observer.contramapFilter(sink)(f))
  }

  def mapIterable[H[_] : Source, A, B](source: H[A])(f: A => Iterable[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[H].subscribe(source)(Observer.contramapIterable(sink)(f))
  }

  @inline def flattenIterable[H[_] : Source, A, B](source: H[Iterable[A]]): Observable[A] = mapIterable(source)(identity)

  def collect[H[_] : Source, A, B](source: H[A])(f: PartialFunction[A, B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[H].subscribe(source)(Observer.contracollect(sink)(f))
  }

  def filter[H[_] : Source, A](source: H[A])(f: A => Boolean): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = Source[H].subscribe(source)(Observer.contrafilter[G, A](sink)(f))
  }

  def scan0[H[_] : Source, A, B](source: H[A])(seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      Sink[G].onNext(sink)(seed)
      Source[H].subscribe(source)(Observer.contrascan[G, B, A](sink)(seed)(f))
    }
  }

  def scan[H[_] : Source, A, B](source: H[A])(seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[H].subscribe(source)(Observer.contrascan[G, B, A](sink)(seed)(f))
  }

  def mapEither[H[_] : Source, A, B](source: H[A])(f: A => Either[Throwable, B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[H].subscribe(source)(Observer.contramapEither(sink)(f))
  }

  def recoverToEither[H[_] : Source, A](source: H[A]): Observable[Either[Throwable, A]] = Observable.map[H, A, Either[Throwable, A]](source)(Right(_)).recover { case err => Left(err) }

  def recover[H[_] : Source, A](source: H[A])(f: PartialFunction[Throwable, A]): Observable[A] = recoverOption(source)(f andThen (Some(_)))

  def recoverOption[H[_] : Source, A](source: H[A])(f: PartialFunction[Throwable, Option[A]]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Source[H].subscribe(source)(Observer.doOnError(sink) { error =>
        f.lift(error) match {
          case Some(v) => v.foreach(Sink[G].onNext(sink)(_))
          case None => Sink[G].onError(sink)(error)
        }
      })
    }
  }

  def doOnSubscribe[H[_] : Source, A](source: H[A])(f: () => Cancelable): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      val cancelable = f()
      Cancelable.composite(
        Source[H].subscribe(source)(sink),
        cancelable
      )
    }
  }

  def doOnNext[H[_] : Source, A](source: H[A])(f: A => Unit): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Source[H].subscribe(source)(Observer.doOnNext[G, A](sink) { value =>
        f(value)
        Sink[G].onNext(sink)(value)
      })
    }
  }

  def doOnError[H[_] : Source, A](source: H[A])(f: Throwable => Unit): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Source[H].subscribe(source)(Observer.doOnError(sink) { error =>
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

  def mapAsync[H[_] : Source, F[_]: Effect, A, B](sourceA: H[A])(f: A => F[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      val consecutive = Cancelable.consecutive()

      val subscription = Source[H].subscribe(sourceA)(Observer.create[A](
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

  @inline def mapFuture[H[_] : Source, A, B](source: H[A])(f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] = mapAsync(source)(v => IO.fromFuture(IO.pure(f(v)))(IO.contextShift(ec)))

  def mapAsyncSingleOrDrop[H[_] : Source, F[_]: Effect, A, B](sourceA: H[A])(f: A => F[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      val single = Cancelable.singleOrDrop()

      val subscription = Source[H].subscribe(sourceA)(Observer.create[A](
        { value =>
          val effect = f(value)
          single() = { () =>
            //TODO: proper cancel effects?
            var isCancel = false
            Effect[F].runAsync(effect)(either => IO {
              if (!isCancel) {
                either match {
                  case Right(value) => Sink[G].onNext(sink)(value)
                  case Left(error)  => Sink[G].onError(sink)(error)
                }
                single.done()
              }
            }).unsafeRunSync()

            Cancelable(() => isCancel = true)
          }
        },
        Sink[G].onError(sink),
      ))

      Cancelable.composite(subscription, single)
    }
  }

  @inline def mapFutureSingleOrDrop[H[_] : Source, A, B](source: H[A])(f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] = mapAsyncSingleOrDrop(source)(v => IO.fromFuture(IO.pure(f(v)))(IO.contextShift(ec)))

  @inline def mapSync[H[_] : Source, F[_]: RunSyncEffect, A, B](source: H[A])(f: A => F[B]): Observable[B] = map(source)(v => RunSyncEffect[F].unsafeRun(f(v)))

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

  def zipWithIndex[H[_] : Source, A, R](source: H[A]): Observable[(A, Int)] = new Observable[(A, Int)] {
    def subscribe[G[_]: Sink](sink: G[_ >: (A, Int)]): Cancelable = {
      var counter = 0

      Source[H].subscribe(source)(Observer.unsafeCreate[A](
        { value =>
          val index = counter
          counter += 1
          Sink[G].onNext(sink)((value, index))
        },
        Sink[G].onError(sink),
      ))
    }
  }

  @inline def debounce[H[_] : Source, A](source: H[A])(duration: FiniteDuration): Observable[A] = debounceMillis(source)(duration.toMillis.toInt)

  def debounceMillis[H[_] : Source, A](source: H[A])(duration: Int): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var lastTimeout: js.UndefOr[Int] = js.undefined
      var isCancel = false

      Cancelable.composite(
        Cancelable { () =>
          isCancel = true
          lastTimeout.foreach(dom.window.clearTimeout)
        },
        Source[H].subscribe(source)(Observer.create[A](
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

  @inline def sample[H[_] : Source, A](source: H[A])(duration: FiniteDuration): Observable[A] = sampleMillis(source)(duration.toMillis.toInt)

  def sampleMillis[H[_] : Source, A](source: H[A])(duration: Int): Observable[A] = new Observable[A] {
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
        Source[H].subscribe(source)(Observer.unsafeCreate[A](
          value => lastValue = Some(value),
          Sink[G].onError(sink),
        ))
      )
    }
  }

  //TODO setImmediate?
  @inline def async[H[_] : Source, A](source: H[A]): Observable[A] = delayMillis(source)(0)

  @inline def delay[H[_] : Source, A](source: H[A])(duration: FiniteDuration): Observable[A] = delayMillis(source)(duration.toMillis.toInt)

  def delayMillis[H[_] : Source, A](source: H[A])(duration: Int): Observable[A] = new Observable[A] {
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
        Source[H].subscribe(source)(Observer.create[A](
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

  def distinct[H[_] : Source, A : Eq](source: H[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var lastValue: Option[A] = None

      Source[H].subscribe(source)(Observer.unsafeCreate[A](
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

  @inline def distinctOnEquals[H[_] : Source, A](source: H[A]): Observable[A] = distinct(source)(Source[H], Eq.fromUniversalEquals)

  def withDefaultSubscription[H[_] : Source, F[_]: Sink, A](source: H[A])(sink: F[A]): Observable[A] = new Observable[A] {
    private var defaultSubscription = Source[H].subscribe(source)(sink)

    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      // stop the default subscription.
      if (defaultSubscription != null) {
        defaultSubscription.cancel()
        defaultSubscription = null
      }

      Source[H].subscribe(source)(sink)
    }
  }

  @inline def transformSource[F[_], FF[_]: Source, A, B](source: F[A])(transform: F[A] => FF[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[FF].subscribe(transform(source))(sink)
  }

  @inline def transformSink[H[_] : Source, G[_]: Sink, A, B](source: H[A])(transform: Observer[_ >: B] => G[A]): Observable[B] = new Observable[B] {
    def subscribe[GG[_]: Sink](sink: GG[_ >: B]): Cancelable = Source[H].subscribe(source)(transform(Observer.lift(sink)))
  }

  @inline def share[H[_] : Source, A](source: H[A]): Observable[A] = publish(source).refCount

  @inline def publish[H[_] : Source, A](source: H[A]): Observable.Connectable[A] = multicast(source)(Subject.publish[A])
  @inline def replay[H[_] : Source, A](source: H[A]): Observable.ConnectableMaybeValue[A] = multicastMaybeValue(source)(Subject.replay[A])
  @inline def behavior[H[_] : Source, A](source: H[A])(value: A): Observable.ConnectableValue[A] = multicastValue(source)(Subject.behavior[A](value))

  @inline def publishSelector[H[_] : Source, A, B](source: H[A])(f: Observable[A] => Observable[B]): Observable[B] = transformSource(source)(s => f(publish(s).refCount))
  @inline def replaySelector[H[_] : Source, A, B](source: H[A])(f: Observable.MaybeValue[A] => Observable[B]): Observable[B] = transformSource(source)(s => f(replay(s).refCount))
  @inline def behaviorSelector[H[_] : Source, A, B](source: H[A])(value: A)(f: Observable.Value[A] => Observable[B]): Observable[B] = transformSource(source)(s => f(behavior(s)(value).refCount))

  def multicast[HA[_] : Source, A, HB[_] : Source : Sink](source: HA[A])(pipe: HB[A]): Connectable[A] = new Connectable[A] {
    private val refCount: Cancelable.RefCount = Cancelable.refCount(() => Source[HA].subscribe(source)(pipe))
    def connect(): Cancelable = refCount.ref()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = Source[HB].subscribe(pipe)(sink)
  }

  def multicastValue[H[_] : Source, A](source: H[A])(pipe: Subject.Value[A]): ConnectableValue[A] = new Connectable[A] with Value[A] {
    private val refCount: Cancelable.RefCount = Cancelable.refCount(() => Source[H].subscribe(source)(pipe))
    def now(): A = pipe.now()
    def connect(): Cancelable = refCount.ref()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = pipe.subscribe(sink)
  }

  def multicastMaybeValue[H[_] : Source, A](source: H[A])(pipe: Subject.MaybeValue[A]): ConnectableMaybeValue[A] = new Connectable[A] with MaybeValue[A] {
    private val refCount: Cancelable.RefCount = Cancelable.refCount(() => Source[H].subscribe(source)(pipe))
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

  def hot[A](source: Connectable[A]): Observable.Hot[A] = new Observable[A] with Cancelable {
    private val cancelable = source.connect()
    def cancel() = cancelable.cancel()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = source.subscribe(sink)
  }
  def hotValue[A](source: ConnectableValue[A]): Observable.HotValue[A] = new Value[A] with Cancelable {
    private val cancelable = source.connect()
    def cancel() = cancelable.cancel()
    def now() = source.now()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = source.subscribe(sink)
  }
  def hotMaybeValue[A](source: ConnectableMaybeValue[A]): Observable.HotMaybeValue[A] = new Observable.MaybeValue[A] with Cancelable {
    private val cancelable = source.connect()
    def cancel() = cancelable.cancel()
    def now() = source.now()
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = source.subscribe(sink)
  }

  @inline def prependSync[H[_] : Source, A, F[_] : RunSyncEffect](source: H[A])(value: F[A]): Observable[A] = concatSync[F, A, H](value, source)
  @inline def prependAsync[H[_] : Source, A, F[_] : Effect](source: H[A])(value: F[A]): Observable[A] = concatAsync[F, A, H](value, source)
  @inline def prependFuture[H[_] : Source, A](source: H[A])(value: Future[A])(implicit ec: ExecutionContext): Observable[A] = concatFuture[A, H](value, source)

  def prepend[H[_] : Source, A](source: H[A])(value: A): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      Sink[G].onNext(sink)(value)
      Source[H].subscribe(source)(sink)
    }
  }

  def startWith[H[_] : Source, A](source: H[A])(values: Iterable[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      values.foreach(Sink[G].onNext(sink))
      Source[H].subscribe(source)(sink)
    }
  }

  @inline def head[H[_] : Source, A](source: H[A]): Observable[A] = take(source)(1)

  def take[H[_] : Source, A](source: H[A])(num: Int): Observable[A] = {
    if (num <= 0) Observable.empty
    else new Observable[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
        var counter = 0
        val subscription = Cancelable.variable()
        subscription() = Source[H].subscribe(source)(Observer.contrafilter(sink) { _ =>
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

  def takeWhile[H[_] : Source, A](source: H[A])(predicate: A => Boolean): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var finishedTake = false
      val subscription = Cancelable.variable()
      subscription() = Source[H].subscribe(source)(Observer.contrafilter[G, A](sink) { v =>
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

  def takeUntil[H[_] : Source, FU[_]: Source, A](source: H[A])(until: FU[Unit]): Observable[A] = new Observable[A] {
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

      if (!finishedTake) subscription += Source[H].subscribe(source)(Observer.contrafilter[G, A](sink)(_ => !finishedTake))

      subscription
    }
  }

  def drop[H[_] : Source, A](source: H[A])(num: Int): Observable[A] = {
    if (num <= 0) Observable.lift(source)
    else new Observable[A] {
      def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
        var counter = 0
        Source[H].subscribe(source)(Observer.contrafilter(sink) { _ =>
          if (num > counter) {
            counter += 1
            false
          } else true
        })
      }
    }
  }

  def dropWhile[H[_] : Source, A](source: H[A])(predicate: A => Boolean): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      var finishedDrop = false
      Source[H].subscribe(source)(Observer.contrafilter[G, A](sink) { v =>
        if (finishedDrop) true
        else if (predicate(v)) false
        else {
          finishedDrop = true
          true
        }
      })
    }
  }

  def dropUntil[H[_] : Source, FU[_]: Source, A](source: H[A])(until: FU[Unit]): Observable[A] = new Observable[A] {
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

      val subscription = Source[H].subscribe(source)(Observer.contrafilter[G, A](sink)(_ => finishedDrop))

      Cancelable.composite(subscription, untilCancelable)
    }
  }

  def resource[F[_] : RunSyncEffect, R, S](acquire: F[R], use: R => S, release: R => F[Unit]): Observable[S] = resourceFunction[R, S](() => RunSyncEffect[F].unsafeRun(acquire), use, r => RunSyncEffect[F].unsafeRun(release(r)))

  def resourceFunction[R, S](acquire: () => R, use: R => S, release: R => Unit): Observable[S] = new Observable[S] {
    def subscribe[G[_]: Sink](sink: G[_ >: S]): Cancelable = {
      val r = acquire()
      val s = use(r)
      Sink[G].onNext(sink)(s)
      Cancelable(() => release(r))
    }
  }

  @inline implicit class Operations[A](val source: Observable[A]) extends AnyVal {
    @inline def liftSource[G[_]: LiftSource]: G[A] = LiftSource[G].lift(source)
    @inline def failed: Observable[Throwable] = Observable.failed(source)
    @inline def via[G[_]: Sink](sink: G[A]): Observable[A] = Observable.via(source)(sink)
    @inline def mergeMap[H[_] : Source, B](f: A => H[B]): Observable[B] = Observable.mergeMap(source)(f)
    @inline def switchMap[H[_] : Source, B](f: A => H[B]): Observable[B] = Observable.switchMap(source)(f)
    @inline def zip[H[_] : Source, B](combined: H[B]): Observable[(A,B)] = Observable.zip(source, combined)
    @inline def zipMap[H[_] : Source, B, R](combined: H[B])(f: (A, B) => R): Observable[R] = Observable.zipMap(source, combined)(f)
    @inline def combineLatest[H[_] : Source, B](combined: H[B]): Observable[(A,B)] = Observable.combineLatest(source, combined)
    @inline def combineLatestMap[H[_] : Source, B, R](combined: H[B])(f: (A, B) => R): Observable[R] = Observable.combineLatestMap(source, combined)(f)
    @inline def withLatest[H[_] : Source, B](latest: H[B]): Observable[(A,B)] = Observable.withLatest(source, latest)
    @inline def withLatestMap[H[_] : Source, B, R](latest: H[B])(f: (A, B) => R): Observable[R] = Observable.withLatestMap(source, latest)(f)
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
    @inline def mapFuture[B](f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] = Observable.mapFuture(source)(f)
    @inline def mapAsync[G[_]: Effect, B](f: A => G[B]): Observable[B] = Observable.mapAsync(source)(f)
    @inline def mapFutureSingleOrDrop[B](f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] = Observable.mapFutureSingleOrDrop(source)(f)
    @inline def mapAsyncSingleOrDrop[G[_]: Effect, B](f: A => G[B]): Observable[B] = Observable.mapAsyncSingleOrDrop(source)(f)
    @inline def mapSync[G[_]: RunSyncEffect, B](f: A => G[B]): Observable[B] = Observable.mapSync(source)(f)
    @inline def map[B](f: A => B): Observable[B] = Observable.map(source)(f)
    @inline def mapIterable[B](f: A => Iterable[B]): Observable[B] = Observable.mapIterable(source)(f)
    @inline def mapEither[B](f: A => Either[Throwable, B]): Observable[B] = Observable.mapEither(source)(f)
    @inline def mapFilter[B](f: A => Option[B]): Observable[B] = Observable.mapFilter(source)(f)
    @inline def doOnSubscribe(f: () => Cancelable): Observable[A] = Observable.doOnSubscribe(source)(f)
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
    @inline def transformSource[H[_] : Source, B](transform: Observable[A] => H[B]): Observable[B] = Observable.transformSource(source)(transform)
    @inline def transformSink[G[_]: Sink, B](transform: Observer[_ >: B] => G[A]): Observable[B] = Observable.transformSink[Observable, G, A, B](source)(transform)
    @inline def prepend(value: A): Observable[A] = Observable.prepend(source)(value)
    @inline def prependSync[F[_] : RunSyncEffect](value: F[A]): Observable[A] = Observable.prependSync(source)(value)
    @inline def prependAsync[F[_] : Effect](value: F[A]): Observable[A] = Observable.prependAsync(source)(value)
    @inline def prependFuture(value: Future[A])(implicit ec: ExecutionContext): Observable[A] = Observable.prependFuture(source)(value)
    @inline def startWith(values: Iterable[A]): Observable[A] = Observable.startWith(source)(values)
    @inline def head: Observable[A] = Observable.head(source)
    @inline def take(num: Int): Observable[A] = Observable.take(source)(num)
    @inline def takeWhile(predicate: A => Boolean): Observable[A] = Observable.takeWhile(source)(predicate)
    @inline def takeUntil[H[_] : Source](until: H[Unit]): Observable[A] = Observable.takeUntil(source)(until)
    @inline def drop(num: Int): Observable[A] = Observable.drop(source)(num)
    @inline def dropWhile(predicate: A => Boolean): Observable[A] = Observable.dropWhile(source)(predicate)
    @inline def dropUntil[H[_] : Source](until: H[Unit]): Observable[A] = Observable.dropUntil(source)(until)
    @inline def withDefaultSubscription[G[_] : Sink](sink: G[A]): Observable[A] = Observable.withDefaultSubscription(source)(sink)
    @inline def subscribe(): Cancelable = source.subscribe(Observer.empty)
    @inline def foreach(f: A => Unit): Cancelable = source.subscribe(Observer.create(f))
  }

  @inline implicit class IterableOperations[A](val source: Observable[Iterable[A]]) extends AnyVal {
    @inline def flattenIterable[B]: Observable[A] = Observable.flattenIterable(source)
  }

  @inline implicit class ConnectableOperations[A](val source: Observable.Connectable[A]) extends AnyVal {
    @inline def refCount: Observable[A] = Observable.refCount(source)
    @inline def hot: Observable.Hot[A] = Observable.hot(source)
  }
  @inline implicit class ConnectableValueOperations[A](val source: Observable.ConnectableValue[A]) extends AnyVal {
    @inline def refCount: Observable.Value[A] = Observable.refCountValue(source)
    @inline def hot: Observable.HotValue[A] = Observable.hotValue(source)
  }
  @inline implicit class ConnectableMaybeValueOperations[A](val source: Observable.ConnectableMaybeValue[A]) extends AnyVal {
    @inline def refCount: Observable.MaybeValue[A] = Observable.refCountMaybeValue(source)
    @inline def hot: Observable.HotMaybeValue[A] = Observable.hotMaybeValue(source)
  }

  @inline implicit class SyncEventOperations[EV <: dom.Event](val source: Synchronous[EV]) extends AnyVal {
    @inline private def withOperator(newOperator: EV => Unit): Synchronous[EV] = new Synchronous(source.map { ev => newOperator(ev); ev })

    @inline def preventDefault: Synchronous[EV] = withOperator(_.preventDefault())
    @inline def stopPropagation: Synchronous[EV] = withOperator(_.stopPropagation())
    @inline def stopImmediatePropagation: Synchronous[EV] = withOperator(_.stopImmediatePropagation())
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

  @inline implicit class ProSubjectOperations[I,O](val handler: ProSubject[I,O]) extends AnyVal {
    @inline def transformSubjectSourceVaried[H[_] : Source, O2](g: Observable[O] => H[O2]): ProSubject[I, O2] = ProSubject.from[Observer, H, I, O2](handler, g(handler))
    @inline def transformSubjectSinkVaried[G[_] : Sink, I2](f: Observer[I] => G[I2]): ProSubject[I2, O] = ProSubject.from[G, Observable, I2, O](f(handler), handler)
    @inline def transformProSubjectVaried[G[_] : Sink, H[_] : Source, I2, O2](f: Observer[I] => G[I2])(g: Observable[O] => H[O2]): ProSubject[I2, O2] = ProSubject.from[G, H, I2, O2](f(handler), g(handler))
    @inline def transformSubjectSource[O2](g: Observable[O] => Observable[O2]): ProSubject[I, O2] = transformSubjectSourceVaried(g)
    @inline def transformSubjectSink[I2](f: Observer[I] => Observer[I2]): ProSubject[I2, O] = transformSubjectSinkVaried(f)
    @inline def transformProSubject[I2, O2](f: Observer[I] => Observer[I2])(g: Observable[O] => Observable[O2]): ProSubject[I2, O2] = transformProSubjectVaried(f)(g)
    @inline def imapProSubject[I2, O2](f: I2 => I)(g: O => O2): ProSubject[I2, O2] = transformProSubject(_.contramap(f))(_.map(g))
  }

  @inline implicit class SubjectOperations[A](val handler: Subject[A]) extends AnyVal {
    @inline def transformSubjectVaried[G[_] : Sink, H[_] : Source, A2](f: Observer[A] => G[A2])(g: Observable[A] => H[A2]): Subject[A2] = handler.transformProSubjectVaried(f)(g)
    @inline def transformSubject[A2](f: Observer[A] => Observer[A2])(g: Observable[A] => Observable[A2]): Subject[A2] = handler.transformProSubjectVaried(f)(g)
    @inline def imapSubject[A2](f: A2 => A)(g: A => A2): Subject[A2] = handler.transformSubject(_.contramap(f))(_.map(g))
  }

  private def recovered[T](action: => Unit, onError: Throwable => Unit) = try action catch { case NonFatal(t) => onError(t) }
}
