package colibri

import colibri.effect._

import cats.{MonoidK, Applicative, FunctorFilter, Eq, Semigroupal}
import cats.effect.{Effect, IO}

import scala.scalajs.js
import scala.scalajs.js.timers

import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

trait Observable[+A] {
  //TODO: def subscribe[F[_] : Sync](sink: Observer[A]): F[Cancelable]
  def subscribe(sink: Observer[A]): Cancelable
}
object Observable    {

  implicit object source extends Source[Observable] {
    @inline def subscribe[A](source: Observable[A])(sink: Observer[A]): Cancelable = source.subscribe(sink)
  }

  implicit object liftSource extends LiftSource[Observable] {
    @inline def lift[H[_]: Source, A](source: H[A]): Observable[A] = Observable.lift[H, A](source)
  }

  implicit object monoidK extends MonoidK[Observable] {
    @inline def empty[T]                                        = Observable.empty
    @inline def combineK[T](a: Observable[T], b: Observable[T]) = Observable.merge(a, b)
  }

  implicit object applicative extends Applicative[Observable] {
    @inline def ap[A, B](ff: Observable[A => B])(fa: Observable[A]): Observable[B] = ff.combineLatestMap(fa)((f, a) => f(a))
    @inline def pure[A](a: A): Observable[A]                                       = Observable(a)
    @inline override def map[A, B](fa: Observable[A])(f: A => B): Observable[B]    = fa.map(f)
  }

  implicit object functorFilter extends FunctorFilter[Observable] {
    @inline def functor                                                              = Observable.applicative
    @inline def mapFilter[A, B](fa: Observable[A])(f: A => Option[B]): Observable[B] = fa.mapFilter(f)
  }

  implicit object semigroupal extends Semigroupal[Observable] {
    @inline def product[A, B](fa: Observable[A], fb: Observable[B]): Observable[(A, B)] = fa.combineLatest(fb)
  }

  implicit object createSubject extends CreateSubject[Subject] {
    @inline def publish[A]: Subject[A]           = Subject.publish[A]
    @inline def replay[A]: Subject[A]            = Subject.replay[A]
    @inline def behavior[A](seed: A): Subject[A] = Subject.behavior[A](seed)
  }

  implicit object createProSubject extends CreateProSubject[ProSubject] {
    @inline def from[GI[_]: Sink, HO[_]: Source, I, O](sink: GI[I], source: HO[O]): ProSubject[I, O] =
      ProSubject.from(Observer.lift(sink), Observable.lift(source))
  }

  trait Value[+A]       extends Observable[A] {
    def now(): A
  }
  trait MaybeValue[+A]  extends Observable[A] {
    def now(): Option[A]
  }

  type Hot[+A]           = Observable[A] with Cancelable
  type HotValue[+A]      = Value[A] with Cancelable
  type HotMaybeValue[+A] = MaybeValue[A] with Cancelable

  object Empty extends Observable[Nothing] {
    @inline def subscribe(sink: Observer[Nothing]): Cancelable = Cancelable.empty
  }

  @inline def empty = Empty

  def apply[T](value: T): Observable[T] = new Observable[T] {
    def subscribe(sink: Observer[T]): Cancelable = {
      sink.onNext(value)
      Cancelable.empty
    }
  }

  def failure[T](error: Throwable): Observable[T] = new Observable[T] {
    def subscribe(sink: Observer[T]): Cancelable = {
      sink.onError(error)
      Cancelable.empty
    }
  }

  def fromIterable[T](values: Iterable[T]): Observable[T] = new Observable[T] {
    def subscribe(sink: Observer[T]): Cancelable = {
      values.foreach(sink.onNext)
      Cancelable.empty
    }
  }

  def lift[H[_]: Source, A](source: H[A]): Observable[A] = source match {
    case source: Observable[A @unchecked] => source
    case _                                =>
      new Observable[A] {
        def subscribe(sink: Observer[A]): Cancelable = Source[H].subscribe(source)(sink)
      }
  }

  @inline def create[A](produce: Observer[A] => Cancelable): Observable[A] = new Observable[A] {
    def subscribe(sink: Observer[A]): Cancelable = produce(sink)
  }

  def fromEither[A](value: Either[Throwable, A]): Observable[A] = new Observable[A] {
    def subscribe(sink: Observer[A]): Cancelable = {
      value match {
        case Right(a)    => sink.onNext(a)
        case Left(error) => sink.onError(error)
      }
      Cancelable.empty
    }
  }

  def fromSync[F[_]: RunSyncEffect, A](effect: F[A]): Observable[A] = new Observable[A] {
    def subscribe(sink: Observer[A]): Cancelable = {
      recovered(sink.onNext(RunSyncEffect[F].unsafeRun(effect)), sink.onError(_))
      Cancelable.empty
    }
  }

  def fromAsync[F[_]: Effect, A](effect: F[A]): Observable[A] = new Observable[A] {
    def subscribe(sink: Observer[A]): Cancelable = {
      //TODO: proper cancel effects?
      var isCancel = false

      Effect[F]
        .runAsync(effect)(either =>
          IO {
            if (!isCancel) {
              isCancel = true
              either match {
                case Right(value) => sink.onNext(value)
                case Left(error)  => sink.onError(error)
              }
            }
          },
        )
        .unsafeRunSync()

      if (isCancel) Cancelable.empty
      else Cancelable(() => isCancel = true)
    }
  }

  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): Observable[A] = fromAsync(
    IO.fromFuture(IO.pure(future))(IO.contextShift(ec)),
  )

  @inline def interval(delay: FiniteDuration): Observable[Long] = intervalMillis(delay.toMillis.toInt)

  def intervalMillis(delay: Int): Observable[Long] = new Observable[Long] {
    def subscribe(sink: Observer[Long]): Cancelable = {
      var isCancel      = false
      var counter: Long = 0

      def send(): Unit = {
        val current = counter
        counter += 1
        sink.onNext(current)
      }

      send()

      val intervalId = timers.setInterval(delay.toDouble) { if (!isCancel) send() }

      Cancelable { () =>
        isCancel = true
        timers.clearInterval(intervalId)
      }
    }
  }

  def concatAsync[F[_]: Effect, T](effects: F[T]*): Observable[T] = fromIterable(effects).mapAsync(identity)

  def concatSync[F[_]: RunSyncEffect, T](effects: F[T]*): Observable[T] = fromIterable(effects).mapSync(identity)

  def concatFuture[T](values: Future[T]*)(implicit ec: ExecutionContext): Observable[T] = fromIterable(values).mapFuture(identity)

  def concatAsync[F[_]: Effect, T](effect: F[T], source: Observable[T]): Observable[T] = new Observable[T] {
    def subscribe(sink: Observer[T]): Cancelable = {
      //TODO: proper cancel effects?
      var isCancel    = false
      val consecutive = Cancelable.consecutive()

      consecutive += (() => Cancelable(() => isCancel = true))
      consecutive += (() => source.subscribe(sink))

      Effect[F]
        .runAsync(effect)(either =>
          IO {
            if (!isCancel) {
              isCancel = true
              either match {
                case Right(value) => sink.onNext(value)
                case Left(error)  => sink.onError(error)
              }
              consecutive.switch()
            }
          },
        )
        .unsafeRunSync()

      consecutive
    }
  }

  def concatFuture[T](value: Future[T], source: Observable[T])(implicit ec: ExecutionContext): Observable[T] =
    concatAsync(IO.fromFuture(IO.pure(value))(IO.contextShift(ec)), source)

  def concatSync[F[_]: RunSyncEffect, T](effect: F[T], source: Observable[T]): Observable[T] = new Observable[T] {
    def subscribe(sink: Observer[T]): Cancelable = {
      recovered(sink.onNext(RunSyncEffect[F].unsafeRun(effect)), sink.onError(_))
      source.subscribe(sink)
    }
  }

  def merge[A](sources: Observable[A]*): Observable[A] = mergeSeq(sources)

  def mergeSeq[A](sources: Seq[Observable[A]]): Observable[A] = new Observable[A] {
    def subscribe(sink: Observer[A]): Cancelable = {
      val subscriptions = sources.map { source =>
        source.subscribe(sink)
      }

      Cancelable.compositeFromIterable(subscriptions)
    }
  }

  def switch[A](sources: Observable[A]*): Observable[A] = switchSeq(sources)

  def switchSeq[A](sources: Seq[Observable[A]]): Observable[A] = new Observable[A] {
    def subscribe(sink: Observer[A]): Cancelable = {
      val variable = Cancelable.variable()
      sources.foreach { source =>
        variable() = source.subscribe(sink)
      }

      variable
    }
  }

  def resource[F[_]: RunSyncEffect, R, S](acquire: F[R], use: R => S, release: R => F[Unit]): Observable[S] =
    resourceFunction[R, S](() => RunSyncEffect[F].unsafeRun(acquire), use, r => RunSyncEffect[F].unsafeRun(release(r)))

  def resourceFunction[R, S](acquire: () => R, use: R => S, release: R => Unit): Observable[S] = new Observable[S] {
    def subscribe(sink: Observer[S]): Cancelable = {
      val r = acquire()
      val s = use(r)
      sink.onNext(s)
      Cancelable(() => release(r))
    }
  }
  @inline implicit class Operations[A](val source: Observable[A]) extends AnyVal {
    def failed: Observable[Throwable] = new Observable[Throwable] {
      def subscribe(sink: Observer[Throwable]): Cancelable =
        source.subscribe(Observer.unsafeCreate(_ => (), sink.onNext(_)))
    }

    def via(sink: Observer[A]): Observable[A] = new Observable[A] {
      def subscribe(sink2: Observer[A]): Cancelable = source.subscribe(Observer.combine(sink, sink2))
    }

    def map[B](f: A => B): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(sink.contramap(f))
    }

    def mapFilter[B](f: A => Option[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(sink.contramapFilter(f))
    }

    def mapIterable[B](f: A => Iterable[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(sink.contramapIterable(f))
    }

    def collect[B](f: PartialFunction[A, B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(sink.contracollect(f))
    }

    def filter(f: A => Boolean): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = source.subscribe(sink.contrafilter(f))
    }

    def scanToList: Observable[List[A]] = scan(List.empty[A])((list, x) => x :: list)

    def scan0ToList: Observable[List[A]] = scan0(List.empty[A])((list, x) => x :: list)

    def scan0[B](seed: B)(f: (B, A) => B): Observable[B] = scan(seed)(f).prepend(seed)

    def scan[B](seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(sink.contrascan(seed)(f))
    }

    def mapEither[B](f: A => Either[Throwable, B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(sink.contramapEither(f))
    }

    def recoverToEither: Observable[Either[Throwable, A]] = source.map[Either[Throwable, A]](Right(_)).recover { case err => Left(err) }

    def recover(f: PartialFunction[Throwable, A]): Observable[A] = recoverOption(f andThen (Some(_)))

    def recoverOption(f: PartialFunction[Throwable, Option[A]]): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        source.subscribe(sink.doOnError { error =>
          f.lift(error) match {
            case Some(v) => v.foreach(sink.onNext(_))
            case None    => sink.onError(error)
          }
        })
      }
    }

    def doOnSubscribe(f: () => Cancelable): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        val cancelable = f()
        Cancelable.composite(
          source.subscribe(sink),
          cancelable,
        )
      }
    }

    def doOnNext(f: A => Unit): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        source.subscribe(sink.doOnNext { value =>
          f(value)
          sink.onNext(value)
        })
      }
    }

    def doOnError(f: Throwable => Unit): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        source.subscribe(sink.doOnError { error =>
          f(error)
          sink.onError(error)
        })
      }
    }

    def mergeMap[B](f: A => Observable[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = {
        val builder = Cancelable.builder()

        val subscription = source.subscribe(
          Observer.create[A](
            { value =>
              val sourceB = f(value)
              builder += sourceB.subscribe(sink)
            },
            sink.onError,
          ),
        )

        Cancelable.composite(subscription, builder)
      }
    }

    def switchMap[B](f: A => Observable[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = {
        val current = Cancelable.variable()

        val subscription = source.subscribe(
          Observer.create[A](
            { value =>
              val sourceB = f(value)
              current() = sourceB.subscribe(sink)
            },
            sink.onError,
          ),
        )

        Cancelable.composite(current, subscription)
      }
    }

    def mapAsync[F[_]: Effect, B](f: A => F[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = {
        val consecutive = Cancelable.consecutive()

        val subscription = source.subscribe(
          Observer.create[A](
            { value =>
              val effect = f(value)
              consecutive += { () =>
                //TODO: proper cancel effects?
                var isCancel = false
                Effect[F]
                  .runAsync(effect)(either =>
                    IO {
                      if (!isCancel) {
                        isCancel = true
                        either match {
                          case Right(value) => sink.onNext(value)
                          case Left(error)  => sink.onError(error)
                        }
                        consecutive.switch()
                      }
                    },
                  )
                  .unsafeRunSync()

                Cancelable(() => isCancel = true)
              }
            },
            sink.onError,
          ),
        )

        Cancelable.composite(subscription, consecutive)
      }
    }

    @inline def mapFuture[B](f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] =
      mapAsync(v => IO.fromFuture(IO.pure(f(v)))(IO.contextShift(ec)))

    def mapAsyncSingleOrDrop[F[_]: Effect, B](f: A => F[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = {
        val single = Cancelable.singleOrDrop()

        val subscription = source.subscribe(
          Observer.create[A](
            { value =>
              val effect = f(value)
              single() = { () =>
                //TODO: proper cancel effects?
                var isCancel = false
                Effect[F]
                  .runAsync(effect)(either =>
                    IO {
                      if (!isCancel) {
                        isCancel = true
                        either match {
                          case Right(value) => sink.onNext(value)
                          case Left(error)  => sink.onError(error)
                        }
                        single.done()
                      }
                    },
                  )
                  .unsafeRunSync()

                Cancelable(() => isCancel = true)
              }
            },
            sink.onError,
          ),
        )

        Cancelable.composite(subscription, single)
      }
    }

    @inline def mapFutureSingleOrDrop[B](f: A => Future[B])(implicit ec: ExecutionContext): Observable[B] =
      mapAsyncSingleOrDrop(v => IO.fromFuture(IO.pure(f(v)))(IO.contextShift(ec)))

    @inline def mapSync[F[_]: RunSyncEffect, B](f: A => F[B]): Observable[B] = map(v => RunSyncEffect[F].unsafeRun(f(v)))

    @inline def zip[B](sourceB: Observable[B]): Observable[(A, B)]                                                             =
      zipMap(sourceB)(_ -> _)
    @inline def zip[B, C](sourceB: Observable[B], sourceC: Observable[C]): Observable[(A, B, C)]                               =
      zipMap(sourceB, sourceC)((a, b, c) => (a, b, c))
    @inline def zip[B, C, D](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D]): Observable[(A, B, C, D)] =
      zipMap(sourceB, sourceC, sourceD)((a, b, c, d) => (a, b, c, d))
    @inline def zip[B, C, D, E](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
    ): Observable[(A, B, C, D, E)]                                                                                             =
      zipMap(sourceB, sourceC, sourceD, sourceE)((a, b, c, d, e) => (a, b, c, d, e))
    @inline def zip[B, C, D, E, F](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    ): Observable[(A, B, C, D, E, F)]                                                                                          =
      zipMap(sourceB, sourceC, sourceD, sourceE, sourceF)((a, b, c, d, e, f) => (a, b, c, d, e, f))

    def zipMap[B, R](sourceB: Observable[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
      def subscribe(sink: Observer[R]): Cancelable = {
        val seqA = new js.Array[A]
        val seqB = new js.Array[B]

        def send(): Unit = if (seqA.nonEmpty && seqB.nonEmpty) {
          val a = seqA.splice(0, deleteCount = 1)(0)
          val b = seqB.splice(0, deleteCount = 1)(0)
          sink.onNext(f(a, b))
        }

        Cancelable.composite(
          source.subscribe(
            Observer.create[A](
              { value =>
                seqA.push(value)
                send()
              },
              sink.onError,
            ),
          ),
          sourceB.subscribe(
            Observer.create[B](
              { value =>
                seqB.push(value)
                send()
              },
              sink.onError,
            ),
          ),
        )
      }
    }

    def zipMap[B, C, R](sourceB: Observable[B], sourceC: Observable[C])(f: (A, B, C) => R): Observable[R]                               =
      zipMap(sourceB.zip(sourceC))((a, tail) => f(a, tail._1, tail._2))
    def zipMap[B, C, D, R](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D])(f: (A, B, C, D) => R): Observable[R] =
      zipMap(sourceB.zip(sourceC, sourceD))((a, tail) => f(a, tail._1, tail._2, tail._3))
    def zipMap[B, C, D, E, R](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D], sourceE: Observable[E])(
        f: (A, B, C, D, E) => R,
    ): Observable[R]                                                                                                                    =
      zipMap(sourceB.zip(sourceC, sourceD, sourceE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
    def zipMap[B, C, D, E, F, R](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    )(f: (A, B, C, D, E, F) => R): Observable[R]                                                                                        =
      zipMap(sourceB.zip(sourceC, sourceD, sourceE, sourceF))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4, tail._5))

    @inline def combineLatest[B](sourceB: Observable[B]): Observable[(A, B)]                                                             =
      combineLatestMap(sourceB)(_ -> _)
    @inline def combineLatest[B, C](sourceB: Observable[B], sourceC: Observable[C]): Observable[(A, B, C)]                               =
      combineLatestMap(sourceB, sourceC)((a, b, c) => (a, b, c))
    @inline def combineLatest[B, C, D](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D]): Observable[(A, B, C, D)] =
      combineLatestMap(sourceB, sourceC, sourceD)((a, b, c, d) => (a, b, c, d))
    @inline def combineLatest[B, C, D, E](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
    ): Observable[(A, B, C, D, E)]                                                                                                       =
      combineLatestMap(sourceB, sourceC, sourceD, sourceE)((a, b, c, d, e) => (a, b, c, d, e))
    @inline def combineLatest[B, C, D, E, F](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    ): Observable[(A, B, C, D, E, F)]                                                                                                    =
      combineLatestMap(sourceB, sourceC, sourceD, sourceE, sourceF)((a, b, c, d, e, f) => (a, b, c, d, e, f))

    def combineLatestMap[B, R](sourceB: Observable[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
      def subscribe(sink: Observer[R]): Cancelable = {
        var latestA: Option[A] = None
        var latestB: Option[B] = None

        def send(): Unit = for {
          a <- latestA
          b <- latestB
        } sink.onNext(f(a, b))

        Cancelable.composite(
          source.subscribe(
            Observer.create[A](
              { value =>
                latestA = Some(value)
                send()
              },
              sink.onError,
            ),
          ),
          sourceB.subscribe(
            Observer.create[B](
              { value =>
                latestB = Some(value)
                send()
              },
              sink.onError,
            ),
          ),
        )
      }
    }

    def combineLatestMap[B, C, R](sourceB: Observable[B], sourceC: Observable[C])(f: (A, B, C) => R): Observable[R] =
      combineLatestMap(sourceB.combineLatest(sourceC))((a, tail) => f(a, tail._1, tail._2))
    def combineLatestMap[B, C, D, R](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D])(
        f: (A, B, C, D) => R,
    ): Observable[R]                                                                                                =
      combineLatestMap(sourceB.combineLatest(sourceC, sourceD))((a, tail) => f(a, tail._1, tail._2, tail._3))
    def combineLatestMap[B, C, D, E, R](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D], sourceE: Observable[E])(
        f: (A, B, C, D, E) => R,
    ): Observable[R]                                                                                                =
      combineLatestMap(sourceB.combineLatest(sourceC, sourceD, sourceE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
    def combineLatestMap[B, C, D, E, F, R](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    )(f: (A, B, C, D, E, F) => R): Observable[R]                                                                    =
      combineLatestMap(sourceB.combineLatest(sourceC, sourceD, sourceE, sourceF))((a, tail) =>
        f(a, tail._1, tail._2, tail._3, tail._4, tail._5),
      )

    def withLatest[B](latest: Observable[B]): Observable[(A, B)]                                                              =
      withLatestMap(latest)(_ -> _)
    def withLatest[B, C](latestB: Observable[B], latestC: Observable[C]): Observable[(A, B, C)]                               =
      withLatestMap(latestB, latestC)((a, b, c) => (a, b, c))
    def withLatest[B, C, D](latestB: Observable[B], latestC: Observable[C], latestD: Observable[D]): Observable[(A, B, C, D)] =
      withLatestMap(latestB, latestC, latestD)((a, b, c, d) => (a, b, c, d))
    def withLatest[B, C, D, E](
        latestB: Observable[B],
        latestC: Observable[C],
        latestD: Observable[D],
        latestE: Observable[E],
    ): Observable[(A, B, C, D, E)]                                                                                            =
      withLatestMap(latestB, latestC, latestD, latestE)((a, b, c, d, e) => (a, b, c, d, e))
    def withLatest[B, C, D, E, F](
        latestB: Observable[B],
        latestC: Observable[C],
        latestD: Observable[D],
        latestE: Observable[E],
        latestF: Observable[F],
    ): Observable[(A, B, C, D, E, F)]                                                                                         =
      withLatestMap(latestB, latestC, latestD, latestE, latestF)((a, b, c, d, e, f) => (a, b, c, d, e, f))

    def withLatestMap[B, R](latest: Observable[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
      def subscribe(sink: Observer[R]): Cancelable = {
        var latestValue: Option[B] = None

        Cancelable.composite(
          latest.subscribe(
            Observer.unsafeCreate[B](
              value => latestValue = Some(value),
              sink.onError,
            ),
          ),
          source.subscribe(
            Observer.create[A](
              value => latestValue.foreach(latestValue => sink.onNext(f(value, latestValue))),
              sink.onError,
            ),
          ),
        )
      }
    }

    def withLatestMap[B, C, R](latestB: Observable[B], latestC: Observable[C])(f: (A, B, C) => R): Observable[R] =
      withLatestMap(latestB.combineLatest(latestC))((a, tail) => f(a, tail._1, tail._2))
    def withLatestMap[B, C, D, R](latestB: Observable[B], latestC: Observable[C], latestD: Observable[D])(
        f: (A, B, C, D) => R,
    ): Observable[R]                                                                                             =
      withLatestMap(latestB.combineLatest(latestC, latestD))((a, tail) => f(a, tail._1, tail._2, tail._3))
    def withLatestMap[B, C, D, E, R](latestB: Observable[B], latestC: Observable[C], latestD: Observable[D], latestE: Observable[E])(
        f: (A, B, C, D, E) => R,
    ): Observable[R]                                                                                             =
      withLatestMap(latestB.combineLatest(latestC, latestD, latestE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
    def withLatestMap[B, C, D, E, F, R](
        latestB: Observable[B],
        latestC: Observable[C],
        latestD: Observable[D],
        latestE: Observable[E],
        latestF: Observable[F],
    )(f: (A, B, C, D, E, F) => R): Observable[R]                                                                 =
      withLatestMap(latestB.combineLatest(latestC, latestD, latestE, latestF))((a, tail) =>
        f(a, tail._1, tail._2, tail._3, tail._4, tail._5),
      )
    def withLatestMap[B, C, D, E, F, G, R](
        latestB: Observable[B],
        latestC: Observable[C],
        latestD: Observable[D],
        latestE: Observable[E],
        latestF: Observable[F],
        latestG: Observable[G],
    )(f: (A, B, C, D, E, F, G) => R): Observable[R]                                                              =
      withLatestMap(latestB.combineLatest(latestC, latestD, latestE, latestF, latestG))((a, tail) =>
        f(a, tail._1, tail._2, tail._3, tail._4, tail._5, tail._6),
      )

    def zipWithIndex[R]: Observable[(A, Int)] = new Observable[(A, Int)] {
      def subscribe(sink: Observer[(A, Int)]): Cancelable = {
        var counter = 0

        source.subscribe(
          Observer.unsafeCreate(
            { value =>
              val index = counter
              counter += 1
              sink.onNext((value, index))
            },
            sink.onError,
          ),
        )
      }
    }

    @inline def debounce(duration: FiniteDuration): Observable[A] = debounceMillis(duration.toMillis.toInt)

    def debounceMillis(duration: Int): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[timers.SetTimeoutHandle] = js.undefined
        var isCancel                                         = false

        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            lastTimeout.foreach(timers.clearTimeout)
          },
          source.subscribe(
            Observer.create[A](
              { value =>
                lastTimeout.foreach { id =>
                  timers.clearTimeout(id)
                }
                lastTimeout = timers.setTimeout(duration.toDouble) {
                  if (!isCancel) sink.onNext(value)
                }
              },
              sink.onError,
            ),
          ),
        )
      }
    }

    @inline def sample(duration: FiniteDuration): Observable[A] = sampleMillis(duration.toMillis.toInt)

    def sampleMillis(duration: Int): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var isCancel             = false
        var lastValue: Option[A] = None

        def send(): Unit = {
          lastValue.foreach(sink.onNext)
          lastValue = None
        }

        val intervalId = timers.setInterval(duration.toDouble) { if (!isCancel) send() }

        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            timers.clearInterval(intervalId)
          },
          source.subscribe(
            Observer.unsafeCreate(
              value => lastValue = Some(value),
              sink.onError,
            ),
          ),
        )
      }
    }

    @inline def async: Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[NativeTypes.SetImmediateHandle] = js.undefined
        var isCancel                                         = false

        // TODO: we only actually cancel the last timeout. The check isCancel
        // makes sure that cancelled subscription is really respected.
        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            lastTimeout.foreach(NativeTypes.clearImmediateRef)
          },
          source.subscribe(
            Observer.create[A](
              { value =>
                lastTimeout = NativeTypes.setImmediateRef { () =>
                  if (!isCancel) sink.onNext(value)
                }
              },
              sink.onError,
            ),
          ),
        )
      }
    }

    @inline def delay(duration: FiniteDuration): Observable[A] = delayMillis(duration.toMillis.toInt)

    def delayMillis(duration: Int): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[timers.SetTimeoutHandle] = js.undefined
        var isCancel                                         = false

        // TODO: we only actually cancel the last timeout. The check isCancel
        // makes sure that cancelled subscription is really respected.
        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            lastTimeout.foreach(timers.clearTimeout)
          },
          source.subscribe(
            Observer.create[A](
              { value =>
                lastTimeout = timers.setTimeout(duration.toDouble) {
                  if (!isCancel) sink.onNext(value)
                }
              },
              sink.onError,
            ),
          ),
        )
      }
    }

    def distinctBy[B](f: A => B)(implicit equality: Eq[B]): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var lastValue: Option[B] = None

        source.subscribe(
          Observer.unsafeCreate(
            { value =>
              val valueB = f(value)
              val shouldSend = lastValue.forall(lastValue => !equality.eqv(lastValue, valueB))
              if (shouldSend) {
                lastValue = Some(valueB)
                sink.onNext(value)
              }
            },
            sink.onError,
          ),
        )
      }
    }

    @inline def distinct(implicit equality: Eq[A]): Observable[A] = distinctBy(identity)

    @inline def distinctByOnEquals[B](f: A => B): Observable[A] = distinctBy(f)(Eq.fromUniversalEquals)
    @inline def distinctOnEquals: Observable[A] = distinct(Eq.fromUniversalEquals)

    def withDefaultSubscription(sink: Observer[A]): Observable[A] = new Observable[A] {
      private var defaultSubscription = source.subscribe(sink)

      def subscribe(sink: Observer[A]): Cancelable = {
        // stop the default subscription.
        if (defaultSubscription != null) {
          defaultSubscription.cancel()
          defaultSubscription = null
        }

        source.subscribe(sink)
      }
    }

    @inline def transformSource[B](transform: Observable[A] => Observable[B]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = transform(source).subscribe(sink)
    }

    @inline def transformSink[B](transform: Observer[B] => Observer[A]): Observable[B] = new Observable[B] {
      def subscribe(sink: Observer[B]): Cancelable = source.subscribe(transform(sink))
    }

    @inline def publish: Connectable[Observable[A]]                  = multicast(Subject.publish[A])
    @inline def replay: Connectable[Observable.MaybeValue[A]]        = multicastMaybeValue(Subject.replay[A])
    @inline def behavior(value: A): Connectable[Observable.Value[A]] = multicastValue(Subject.behavior(value))

    @inline def publishSelector[B](f: Observable[A] => Observable[B]): Observable[B]                  = transformSource(s => f(s.publish.refCount))
    @inline def replaySelector[B](f: Observable.MaybeValue[A] => Observable[B]): Observable[B]        = transformSource(s => f(s.replay.refCount))
    @inline def behaviorSelector[B](value: A)(f: Observable.Value[A] => Observable[B]): Observable[B] =
      transformSource(s => f(s.behavior(value).refCount))

    def multicast(pipe: Subject[A]): Connectable[Observable[A]] = Connectable(new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = Source[Observable].subscribe(pipe)(sink)
    }, () => source.subscribe(pipe))

    def multicastValue(pipe: Subject.Value[A]): Connectable[Observable.Value[A]] = Connectable(new Value[A] {
      def now(): A                                 = pipe.now()
      def subscribe(sink: Observer[A]): Cancelable = pipe.subscribe(sink)
    }, () => source.subscribe(pipe))

    def multicastMaybeValue(pipe: Subject.MaybeValue[A]): Connectable[Observable.MaybeValue[A]] = Connectable(new MaybeValue[A] {
      def now(): Option[A]                         = pipe.now()
      def subscribe(sink: Observer[A]): Cancelable = pipe.subscribe(sink)
    }, () => source.subscribe(pipe))

    @inline def prependSync[F[_]: RunSyncEffect](value: F[A]): Observable[A]                  = concatSync[F, A](value, source)
    @inline def prependAsync[F[_]: Effect](value: F[A]): Observable[A]                        = concatAsync[F, A](value, source)
    @inline def prependFuture(value: Future[A])(implicit ec: ExecutionContext): Observable[A] = concatFuture[A](value, source)

    def prepend(value: A): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        sink.onNext(value)
        source.subscribe(sink)
      }
    }

    def startWith(values: Iterable[A]): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        values.foreach(sink.onNext)
        source.subscribe(sink)
      }
    }

    def headIO: IO[A] = IO.cancelable[A] { callback =>
      val cancelable = Cancelable.variable()
      var isDone     = false

      def dispatch(value: Either[Throwable, A]) = if (!isDone) {
        isDone = true
        cancelable.cancel()
        callback(value)
      }

      cancelable() = source.subscribe(Observer.create[A](value => dispatch(Right(value)), error => dispatch(Left(error))))

      IO(cancelable.cancel())
    }

    @inline def head: Observable[A] = take(1)

    def take(num: Int): Observable[A] = {
      if (num <= 0) Observable.empty
      else
        new Observable[A] {
          def subscribe(sink: Observer[A]): Cancelable = {
            var counter      = 0
            val subscription = Cancelable.variable()
            subscription() = source.subscribe(sink.contrafilter { _ =>
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

    def takeWhile(predicate: A => Boolean): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var finishedTake = false
        val subscription = Cancelable.variable()
        subscription() = source.subscribe(sink.contrafilter { v =>
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

    def takeUntil(until: Observable[Unit]): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var finishedTake = false
        val subscription = Cancelable.builder()

        subscription += until.subscribe(
          Observer.unsafeCreate[Unit](
            { _ =>
              finishedTake = true
              subscription.cancel()
            },
            sink.onError(_),
          ),
        )

        if (!finishedTake) subscription += source.subscribe(sink.contrafilter(_ => !finishedTake))

        subscription
      }
    }

    def drop(num: Int): Observable[A] = {
      if (num <= 0) source
      else
        new Observable[A] {
          def subscribe(sink: Observer[A]): Cancelable = {
            var counter = 0
            source.subscribe(sink.contrafilter { _ =>
              if (num > counter) {
                counter += 1
                false
              } else true
            })
          }
        }
    }

    def dropWhile(predicate: A => Boolean): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var finishedDrop = false
        source.subscribe(sink.contrafilter { v =>
          if (finishedDrop) true
          else if (predicate(v)) false
          else {
            finishedDrop = true
            true
          }
        })
      }
    }

    def dropUntil(until: Observable[Unit]): Observable[A] = new Observable[A] {
      def subscribe(sink: Observer[A]): Cancelable = {
        var finishedDrop = false

        val untilCancelable = Cancelable.variable()
        untilCancelable() = until.subscribe(
          Observer.unsafeCreate[Unit](
            { _ =>
              finishedDrop = true
              untilCancelable.cancel()
            },
            sink.onError(_),
          ),
        )

        val subscription = source.subscribe(sink.contrafilter(_ => finishedDrop))

        Cancelable.composite(subscription, untilCancelable)
      }
    }

    @inline def subscribe(): Cancelable           = source.subscribe(Observer.empty)
    @inline def foreach(f: A => Unit): Cancelable = source.subscribe(Observer.create(f))
  }

  @inline implicit class IterableOperations[A](val source: Observable[Iterable[A]]) extends AnyVal {
    @inline def flattenIterable[B]: Observable[A] = source.mapIterable(identity)
  }

  @inline implicit class SubjectValueOperations[A](val handler: Subject.Value[A]) extends AnyVal {
    def lens[B](read: A => B)(write: (A, B) => A): Subject.Value[B] = new Observer[B] with Observable.Value[B] {
      @inline def now()                                    = read(handler.now())
      @inline def onNext(value: B): Unit                   = handler.onNext(write(handler.now(), value))
      @inline def onError(error: Throwable): Unit          = handler.onError(error)
      @inline def subscribe(sink: Observer[B]): Cancelable = handler.map(read).subscribe(sink)
    }
  }

  @inline implicit class SubjectMaybeValueOperations[A](val handler: Subject.MaybeValue[A]) extends AnyVal {
    def lens[B](seed: => A)(read: A => B)(write: (A, B) => A): Subject.MaybeValue[B] = new Observer[B] with Observable.MaybeValue[B] {
      @inline def now()                                    = handler.now().map(read)
      @inline def onNext(value: B): Unit                   = handler.onNext(write(handler.now().getOrElse(seed), value))
      @inline def onError(error: Throwable): Unit          = handler.onError(error)
      @inline def subscribe(sink: Observer[B]): Cancelable = handler.map(read).subscribe(sink)
    }
  }

  @inline implicit class ProSubjectOperations[I, O](val handler: ProSubject[I, O]) extends AnyVal {
    @inline def transformSubjectSource[O2](g: Observable[O] => Observable[O2]): ProSubject[I, O2]                                   =
      ProSubject.from[I, O2](handler, g(handler))
    @inline def transformSubjectSink[I2](f: Observer[I] => Observer[I2]): ProSubject[I2, O]                                         = ProSubject.from[I2, O](f(handler), handler)
    @inline def transformProSubject[I2, O2](f: Observer[I] => Observer[I2])(g: Observable[O] => Observable[O2]): ProSubject[I2, O2] =
      ProSubject.from[I2, O2](f(handler), g(handler))
    @inline def imapProSubject[I2, O2](f: I2 => I)(g: O => O2): ProSubject[I2, O2]                                                  = transformProSubject(_.contramap(f))(_.map(g))
  }

  @inline implicit class SubjectOperations[A](val handler: Subject[A]) extends AnyVal {
    @inline def transformSubject[A2](f: Observer[A] => Observer[A2])(g: Observable[A] => Observable[A2]): Subject[A2] =
      handler.transformProSubject(f)(g)
    @inline def imapSubject[A2](f: A2 => A)(g: A => A2): Subject[A2]                                                  = handler.transformSubject(_.contramap(f))(_.map(g))
  }

  @inline implicit class ListSubjectOperations[A](val handler: Subject[Seq[A]]) extends AnyVal {
    def sequence: Observable[Seq[Subject.Value[A]]] = new Observable[Seq[Subject.Value[A]]] {
      def subscribe(sink: Observer[Seq[Subject.Value[A]]]): Cancelable = {
        handler.subscribe(Observer.create(
          { sequence =>
            sink.onNext(sequence.zipWithIndex.map { case (a, idx) =>
              new Observer[A] with Observable.Value[A] {
                def now(): A = a

                def subscribe(sink: Observer[A]): Cancelable = {
                  sink.onNext(a)
                  Cancelable.empty
                }

                def onNext(value: A): Unit = {
                  handler.onNext(sequence.updated(idx, value))
                }

                def onError(error: Throwable): Unit = {
                  sink.onError(error)
                }
              }
            })
          },
          sink.onError
        ))
      }
    }
  }

  private def recovered[T](action: => Unit, onError: Throwable => Unit) = try action
  catch { case NonFatal(t) => onError(t) }
}
