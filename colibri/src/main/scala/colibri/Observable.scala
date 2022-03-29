package colibri

import cats.implicits._
import colibri.helpers.NativeTypes
import colibri.effect.{RunSyncEffect, RunEffect}
import cats.{Eq, FunctorFilter, MonoidK, Semigroupal, Applicative, Functor}
import cats.effect.{Sync, SyncIO, Async, IO}
import sloth.types.FlatMapError

import scala.scalajs.js
import scala.scalajs.js.timers
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait Observable[+A] {
  def unsafeSubscribe(sink: Observer[A]): Cancelable
}
object Observable    {

  implicit object source extends Source[Observable] {
    @inline def unsafeSubscribe[A](source: Observable[A])(sink: Observer[A]): Cancelable = source.unsafeSubscribe(sink)
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

  implicit object flatMapError extends FlatMapError[Observable, Throwable] {
    def raiseError[B](failure: Throwable): Observable[B]                                    = Observable.raiseError(failure)
    def flatMapEither[A, B](fa: Observable[A])(f: A => Either[Throwable, B]): Observable[B] = fa.mapEither(f)
  }

  trait Value[+A]      extends Observable[A] {
    def now(): A
  }
  trait MaybeValue[+A] extends Observable[A] {
    def now(): Option[A]
  }

  type Hot[+A]           = Observable[A] with Cancelable
  type HotValue[+A]      = Value[A] with Cancelable
  type HotMaybeValue[+A] = MaybeValue[A] with Cancelable

  object Empty extends Observable[Nothing] {
    @inline def unsafeSubscribe(sink: Observer[Nothing]): Cancelable = Cancelable.empty
  }

  @inline def empty = Empty

  def pure[T](value: T): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      sink.unsafeOnNext(value)
      Cancelable.empty
    }
  }

  @inline def apply[T](value: T): Observable[T] = pure(value)

  @inline def apply[T](value: T, value2: T, values: T*): Observable[T] = Observable.fromIterable(value +: value2 +: values)

  @deprecated("Use Observable.raiseError instead", "0.3.0")
  def failure[T](error: Throwable): Observable[T]    = raiseError(error)
  def raiseError[T](error: Throwable): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      sink.unsafeOnError(error)
      Cancelable.empty
    }
  }

  def fromIterable[T](values: Iterable[T]): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      values.foreach(sink.unsafeOnNext)
      Cancelable.empty
    }
  }

  def lift[H[_]: Source, A](source: H[A]): Observable[A] = source match {
    case source: Observable[A @unchecked] => source
    case _                                =>
      new Observable[A] {
        def unsafeSubscribe(sink: Observer[A]): Cancelable = Source[H].unsafeSubscribe(source)(sink)
      }
  }

  @inline def create[A](produce: Observer[A] => Cancelable): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = produce(sink)
  }

  def fromEither[A](value: Either[Throwable, A]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      value match {
        case Right(a)    => sink.unsafeOnNext(a)
        case Left(error) => sink.unsafeOnError(error)
      }
      Cancelable.empty
    }
  }

  @deprecated("Use fromEffect instead", "0.3.0")
  def fromSync[F[_]: RunSyncEffect, A](effect: F[A]): Observable[A] = fromEffect(effect)
  @deprecated("Use fromEffect instead", "0.3.0")
  def fromAsync[F[_]: RunEffect, A](effect: F[A]): Observable[A]    = fromEffect(effect)
  def fromEffect[F[_]: RunEffect, A](effect: F[A]): Observable[A]   = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable =
      RunEffect[F].unsafeRunSyncOrAsyncCancelable[A](effect)(_.fold(sink.unsafeOnError, sink.unsafeOnNext))
  }

  def fromFuture[A](future: => Future[A]): Observable[A] = fromEffect(IO.fromFuture(IO(future)))

  @inline def interval(delay: FiniteDuration): Observable[Long] = intervalMillis(delay.toMillis.toInt)

  def intervalMillis(delay: Int): Observable[Long] = new Observable[Long] {
    def unsafeSubscribe(sink: Observer[Long]): Cancelable = {
      var isCancel      = false
      var counter: Long = 0

      def send(): Unit = {
        val current = counter
        counter += 1
        sink.unsafeOnNext(current)
      }

      send()

      val intervalId = timers.setInterval(delay.toDouble) { if (!isCancel) send() }

      Cancelable { () =>
        isCancel = true
        timers.clearInterval(intervalId)
      }
    }
  }

  @deprecated("Use concatEffect instead", "0.3.0")
  def concatSync[F[_]: RunSyncEffect, T](effects: F[T]*): Observable[T] = concatEffect(effects: _*)
  @deprecated("Use concatEffect instead", "0.3.0")
  def concatAsync[F[_]: RunEffect, T](effects: F[T]*): Observable[T]    = concatEffect(effects: _*)
  def concatEffect[F[_]: RunEffect, T](effects: F[T]*): Observable[T]   = fromIterable(effects).mapEffect(identity)
  def concatFuture[T](values: Future[T]*): Observable[T]                = fromIterable(values).mapFuture(identity)

  @deprecated("Use concatEffect instead", "0.3.0")
  def concatSync[F[_]: RunSyncEffect, T](effect: F[T], source: Observable[T]): Observable[T] = concatEffect(effect, source)
  @deprecated("Use concatEffect instead", "0.3.0")
  def concatAsync[F[_]: RunEffect, T](effect: F[T], source: Observable[T]): Observable[T]    = concatEffect(effect, source)
  def concatEffect[F[_]: RunEffect, T](effect: F[T], source: Observable[T]): Observable[T]   = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      val consecutive = Cancelable.consecutive()
      consecutive += (() =>
        RunEffect[F].unsafeRunSyncOrAsyncCancelable[T](effect) { either =>
          either.fold(sink.unsafeOnError, sink.unsafeOnNext)
          consecutive.switch()
        },
      )
      consecutive += (() => source.unsafeSubscribe(sink))
      consecutive
    }
  }
  def concatFuture[T](value: => Future[T], source: Observable[T]): Observable[T]             =
    concatEffect(IO.fromFuture(IO.pure(value)), source)

  def merge[A](sources: Observable[A]*): Observable[A] = mergeSeq(sources)

  def mergeSeq[A](sources: Seq[Observable[A]]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      val subscriptions = sources.map { source =>
        source.unsafeSubscribe(sink)
      }

      Cancelable.compositeFromIterable(subscriptions)
    }
  }

  def switch[A](sources: Observable[A]*): Observable[A] = switchSeq(sources)

  def switchSeq[A](sources: Seq[Observable[A]]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      val variable = Cancelable.variable()
      sources.foreach { source =>
        variable() = (() => source.unsafeSubscribe(sink))
      }

      variable
    }
  }

  // def resource[F[_]: RunSyncEffect, R, S](acquire: F[R], use: R => S, release: R => F[Unit]): Observable[S] =
  //   resourceFunction[R, S](() => RunSyncEffect[F].unsafeRun(acquire), use, r => RunSyncEffect[F].unsafeRun(release(r)))

  // def resourceFunction[R, S](acquire: () => R, use: R => S, release: R => Unit): Observable[S] = new Observable[S] {
  //   def unsafeSubscribe(sink: Observer[S]): Cancelable = {
  //     val r = acquire()
  //     val s = use(r)
  //     sink.unsafeOnNext(s)
  //     Cancelable(() => release(r))
  //   }
  // }

  @inline implicit class Operations[A](val source: Observable[A]) extends AnyVal {
    def failed: Observable[Throwable] = new Observable[Throwable] {
      def unsafeSubscribe(sink: Observer[Throwable]): Cancelable =
        source.unsafeSubscribe(Observer.createUnrecovered(_ => (), sink.unsafeOnNext(_)))
    }

    def via(sink: Observer[A]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink2: Observer[A]): Cancelable = source.unsafeSubscribe(Observer.combine(sink, sink2))
    }

    // TODO: this will become the normal foreach after the deprecated gready foreach is removed.
    def foreach_(f: A => Unit): Observable[Unit] = to(Observer.create(f))

    def to(sink: Observer[A]): Observable[Unit] = new Observable[Unit] {
      def unsafeSubscribe(sink2: Observer[Unit]): Cancelable = source.unsafeSubscribe(Observer.combine(sink, sink2.contramap(_ => ())))
    }

    def subscribing(f: Observable[Unit]): Observable[A] = tapSubscribe(() => f.unsafeSubscribe())

    def map[B](f: A => B): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contramap(f))
    }


    def discard: Observable[Nothing]                             = Observable.empty.subscribing(void)
    def void: Observable[Unit]                                   = map(_ => ())
    def as[B](value: B): Observable[B]                           = map(_ => value)
    def asDelay[B](value: => B): Observable[B]                   = map(_ => value)
    def asEffect[F[_]: RunEffect, B](value: F[B]): Observable[B] = mapEffect(_ => value)
    def asFuture[B](value: Future[B]): Observable[B]             = mapFuture(_ => value)

    def mapFilter[B](f: A => Option[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contramapFilter(f))
    }

    def mapIterable[B](f: A => Iterable[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contramapIterable(f))
    }

    def collect[B](f: PartialFunction[A, B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contracollect(f))
    }

    def filter(f: A => Boolean): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = source.unsafeSubscribe(sink.contrafilter(f))
    }

    def scanToList: Observable[List[A]] = scan(List.empty[A])((list, x) => x :: list)

    def scan0ToList: Observable[List[A]] = scan0(List.empty[A])((list, x) => x :: list)

    def scan0[B](seed: B)(f: (B, A) => B): Observable[B] = scan(seed)(f).prepend(seed)

    def scan[B](seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contrascan(seed)(f))
    }

    def mapEither[B](f: A => Either[Throwable, B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contramapEither(f))
    }

    def attempt: Observable[Either[Throwable, A]] = new Observable[Either[Throwable, A]] {
      def unsafeSubscribe(sink: Observer[Either[Throwable, A]]): Cancelable =
        source.unsafeSubscribe(Observer.createFromEither(sink.unsafeOnNext))
    }

    @deprecated("Use attempt instead", "0.3.0")
    def recoverToEither: Observable[Either[Throwable, A]] = source.attempt

    def recover(f: PartialFunction[Throwable, A]): Observable[A] = recoverOption(f andThen (Some(_)))

    def recoverOption(f: PartialFunction[Throwable, Option[A]]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        source.unsafeSubscribe(sink.doOnError { error =>
          f.lift(error) match {
            case Some(v) => v.foreach(sink.unsafeOnNext(_))
            case None    => sink.unsafeOnError(error)
          }
        })
      }
    }

    def tapSubscribeEffect[F[_]: RunEffect](f: F[Cancelable]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        val variable = Cancelable.variable()

        val cancelable = RunEffect[F].unsafeRunSyncOrAsyncCancelable[Cancelable](f) {
          case Right(value) => variable.updateExisting(value)
          case Left(error)  => sink.unsafeOnError(error)
        }

        Cancelable.composite(
          cancelable,
          variable,
          source.unsafeSubscribe(sink),
        )
      }
    }

    def tapEffect[F[_]: RunEffect: Functor](f: A => F[Unit]): Observable[A] =
      mapEffect(a => f(a).as(a))

    def tapFailedEffect[F[_]: RunEffect: Applicative](f: Throwable => F[Unit]): Observable[A] =
      attempt.tapEffect(_.swap.traverseTap(f).void).flattenEither

    @deprecated("Use .tapSubscribe(f) instead", "0.3.4")
    def doOnSubscribe(f: () => Cancelable): Observable[A] = tapSubscribe(f)
    def tapSubscribe(f: () => Cancelable): Observable[A]  = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        val cancelable = f()
        Cancelable.composite(
          source.unsafeSubscribe(sink),
          cancelable,
        )
      }
    }

    @deprecated("Use .tap(f) instead", "0.3.4")
    def doOnNext(f: A => Unit): Observable[A] = tap(f)
    def tap(f: A => Unit): Observable[A]      = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        source.unsafeSubscribe(sink.doOnNext { value =>
          f(value)
          sink.unsafeOnNext(value)
        })
      }
    }

    @deprecated("Use .tapFailed(f) instead", "0.3.4")
    def doOnError(f: Throwable => Unit): Observable[A] = tapFailed(f)
    def tapFailed(f: Throwable => Unit): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        source.unsafeSubscribe(sink.doOnError { error =>
          f(error)
          sink.unsafeOnError(error)
        })
      }
    }

    def mergeMap[B](f: A => Observable[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val builder = Cancelable.builder()

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val sourceB = f(value)
              builder += (() => sourceB.unsafeSubscribe(sink))
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(subscription, builder)
      }
    }

    def merge(sources: Observable[A]*): Observable[A] = Observable.mergeSeq(source +: sources)

    def switch(sources: Observable[A]*): Observable[A] = Observable.switchSeq(source +: sources)

    def switchMap[B](f: A => Observable[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val current = Cancelable.variable()

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val sourceB = f(value)
              current() = (() => sourceB.unsafeSubscribe(sink))
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(current, subscription)
      }
    }

    @deprecated("Use mapEffect instead", "0.3.0")
    @inline def mapSync[F[_]: RunSyncEffect, B](f: A => F[B]): Observable[B] = mapEffect(f)
    @deprecated("Use mapEffect instead", "0.3.0")
    def mapAsync[F[_]: RunEffect, B](f: A => F[B]): Observable[B]            = mapEffect(f)
    def mapEffect[F[_]: RunEffect, B](f: A => F[B]): Observable[B]           = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val consecutive = Cancelable.consecutive()

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val effect = f(value)
              consecutive += (() =>
                RunEffect[F].unsafeRunSyncOrAsyncCancelable[B](effect) { either =>
                  either.fold(sink.unsafeOnError, sink.unsafeOnNext)
                  consecutive.switch()
                },
              )
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(subscription, consecutive)
      }
    }

    @inline def mapFuture[B](f: A => Future[B]): Observable[B] = mapEffect(v => IO.fromFuture(IO(f(v))))

    @deprecated("Use mapEffectSingleOrDrop instead", "0.3.0")
    def mapAsyncSingleOrDrop[F[_]: RunEffect, B](f: A => F[B]): Observable[B]  = mapEffectSingleOrDrop(f)
    def mapEffectSingleOrDrop[F[_]: RunEffect, B](f: A => F[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val single = Cancelable.singleOrDrop()

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val effect = f(value)
              single() = (
                  () =>
                    RunEffect[F].unsafeRunSyncOrAsyncCancelable[B](effect) { either =>
                      either.fold(sink.unsafeOnError, sink.unsafeOnNext)
                      single.done()
                    },
              )
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(subscription, single)
      }
    }

    @inline def mapFutureSingleOrDrop[B](f: A => Future[B]): Observable[B] =
      mapEffectSingleOrDrop(v => IO.fromFuture(IO(f(v))))

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
    ): Observable[(A, B, C, D, E)] =
      zipMap(sourceB, sourceC, sourceD, sourceE)((a, b, c, d, e) => (a, b, c, d, e))
    @inline def zip[B, C, D, E, F](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    ): Observable[(A, B, C, D, E, F)] =
      zipMap(sourceB, sourceC, sourceD, sourceE, sourceF)((a, b, c, d, e, f) => (a, b, c, d, e, f))

    def zipMap[B, R](sourceB: Observable[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
      def unsafeSubscribe(sink: Observer[R]): Cancelable = {
        val seqA = new js.Array[A]
        val seqB = new js.Array[B]

        def send(): Unit = if (seqA.nonEmpty && seqB.nonEmpty) {
          val a = seqA.splice(0, deleteCount = 1)(0)
          val b = seqB.splice(0, deleteCount = 1)(0)
          sink.unsafeOnNext(f(a, b))
        }

        Cancelable.composite(
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                seqA.push(value)
                send()
              },
              sink.unsafeOnError,
            ),
          ),
          sourceB.unsafeSubscribe(
            Observer.create[B](
              { value =>
                seqB.push(value)
                send()
              },
              sink.unsafeOnError,
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
    ): Observable[R] =
      zipMap(sourceB.zip(sourceC, sourceD, sourceE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
    def zipMap[B, C, D, E, F, R](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    )(f: (A, B, C, D, E, F) => R): Observable[R] =
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
    ): Observable[(A, B, C, D, E)] =
      combineLatestMap(sourceB, sourceC, sourceD, sourceE)((a, b, c, d, e) => (a, b, c, d, e))
    @inline def combineLatest[B, C, D, E, F](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    ): Observable[(A, B, C, D, E, F)] =
      combineLatestMap(sourceB, sourceC, sourceD, sourceE, sourceF)((a, b, c, d, e, f) => (a, b, c, d, e, f))

    def combineLatestMap[B, R](sourceB: Observable[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
      def unsafeSubscribe(sink: Observer[R]): Cancelable = {
        var latestA: Option[A] = None
        var latestB: Option[B] = None

        def send(): Unit = for {
          a <- latestA
          b <- latestB
        } sink.unsafeOnNext(f(a, b))

        Cancelable.composite(
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                latestA = Some(value)
                send()
              },
              sink.unsafeOnError,
            ),
          ),
          sourceB.unsafeSubscribe(
            Observer.create[B](
              { value =>
                latestB = Some(value)
                send()
              },
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    def combineLatestMap[B, C, R](sourceB: Observable[B], sourceC: Observable[C])(f: (A, B, C) => R): Observable[R] =
      combineLatestMap(sourceB.combineLatest(sourceC))((a, tail) => f(a, tail._1, tail._2))
    def combineLatestMap[B, C, D, R](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D])(
        f: (A, B, C, D) => R,
    ): Observable[R] =
      combineLatestMap(sourceB.combineLatest(sourceC, sourceD))((a, tail) => f(a, tail._1, tail._2, tail._3))
    def combineLatestMap[B, C, D, E, R](sourceB: Observable[B], sourceC: Observable[C], sourceD: Observable[D], sourceE: Observable[E])(
        f: (A, B, C, D, E) => R,
    ): Observable[R] =
      combineLatestMap(sourceB.combineLatest(sourceC, sourceD, sourceE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
    def combineLatestMap[B, C, D, E, F, R](
        sourceB: Observable[B],
        sourceC: Observable[C],
        sourceD: Observable[D],
        sourceE: Observable[E],
        sourceF: Observable[F],
    )(f: (A, B, C, D, E, F) => R): Observable[R] =
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
    ): Observable[(A, B, C, D, E)] =
      withLatestMap(latestB, latestC, latestD, latestE)((a, b, c, d, e) => (a, b, c, d, e))
    def withLatest[B, C, D, E, F](
        latestB: Observable[B],
        latestC: Observable[C],
        latestD: Observable[D],
        latestE: Observable[E],
        latestF: Observable[F],
    ): Observable[(A, B, C, D, E, F)] =
      withLatestMap(latestB, latestC, latestD, latestE, latestF)((a, b, c, d, e, f) => (a, b, c, d, e, f))

    def withLatestMap[B, R](latest: Observable[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
      def unsafeSubscribe(sink: Observer[R]): Cancelable = {
        var latestValue: Option[B] = None

        Cancelable.composite(
          latest.unsafeSubscribe(
            Observer.createUnrecovered[B](
              value => latestValue = Some(value),
              sink.unsafeOnError,
            ),
          ),
          source.unsafeSubscribe(
            Observer.create[A](
              value => latestValue.foreach(latestValue => sink.unsafeOnNext(f(value, latestValue))),
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    def withLatestMap[B, C, R](latestB: Observable[B], latestC: Observable[C])(f: (A, B, C) => R): Observable[R] =
      withLatestMap(latestB.combineLatest(latestC))((a, tail) => f(a, tail._1, tail._2))
    def withLatestMap[B, C, D, R](latestB: Observable[B], latestC: Observable[C], latestD: Observable[D])(
        f: (A, B, C, D) => R,
    ): Observable[R] =
      withLatestMap(latestB.combineLatest(latestC, latestD))((a, tail) => f(a, tail._1, tail._2, tail._3))
    def withLatestMap[B, C, D, E, R](latestB: Observable[B], latestC: Observable[C], latestD: Observable[D], latestE: Observable[E])(
        f: (A, B, C, D, E) => R,
    ): Observable[R] =
      withLatestMap(latestB.combineLatest(latestC, latestD, latestE))((a, tail) => f(a, tail._1, tail._2, tail._3, tail._4))
    def withLatestMap[B, C, D, E, F, R](
        latestB: Observable[B],
        latestC: Observable[C],
        latestD: Observable[D],
        latestE: Observable[E],
        latestF: Observable[F],
    )(f: (A, B, C, D, E, F) => R): Observable[R] =
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
    )(f: (A, B, C, D, E, F, G) => R): Observable[R] =
      withLatestMap(latestB.combineLatest(latestC, latestD, latestE, latestF, latestG))((a, tail) =>
        f(a, tail._1, tail._2, tail._3, tail._4, tail._5, tail._6),
      )

    def zipWithIndex[R]: Observable[(A, Int)] = new Observable[(A, Int)] {
      def unsafeSubscribe(sink: Observer[(A, Int)]): Cancelable = {
        var counter = 0

        source.unsafeSubscribe(
          Observer.createUnrecovered(
            { value =>
              val index = counter
              counter += 1
              sink.unsafeOnNext((value, index))
            },
            sink.unsafeOnError,
          ),
        )
      }
    }

    @inline def debounce(duration: FiniteDuration): Observable[A] = debounceMillis(duration.toMillis.toInt)

    def debounceMillis(duration: Int): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[timers.SetTimeoutHandle] = js.undefined
        var isCancel                                         = false

        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            lastTimeout.foreach(timers.clearTimeout)
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                lastTimeout.foreach { id =>
                  timers.clearTimeout(id)
                }
                lastTimeout = timers.setTimeout(duration.toDouble) {
                  if (!isCancel) sink.unsafeOnNext(value)
                }
              },
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    @inline def sample(duration: FiniteDuration): Observable[A] = sampleMillis(duration.toMillis.toInt)

    def sampleMillis(duration: Int): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var isCancel             = false
        var lastValue: Option[A] = None

        def send(): Unit = {
          lastValue.foreach(sink.unsafeOnNext)
          lastValue = None
        }

        val intervalId = timers.setInterval(duration.toDouble) { if (!isCancel) send() }

        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            timers.clearInterval(intervalId)
          },
          source.unsafeSubscribe(
            Observer.createUnrecovered(
              value => lastValue = Some(value),
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    def asyncMicro: Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var isCancel = false

        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                NativeTypes.queueMicrotask { () =>
                  if (!isCancel) sink.unsafeOnNext(value)
                }
              },
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    @inline def async: Observable[A] = asyncMacro
    def asyncMacro: Observable[A]    = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[NativeTypes.SetImmediateHandle] = js.undefined
        var isCancel                                                = false

        // TODO: we only actually cancel the last timeout. The check isCancel
        // makes sure that unsafeCancelled subscription is really respected.
        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            lastTimeout.foreach(NativeTypes.clearImmediateRef)
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                lastTimeout = NativeTypes.setImmediateRef { () =>
                  if (!isCancel) sink.unsafeOnNext(value)
                }
              },
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    @inline def delay(duration: FiniteDuration): Observable[A] = delayMillis(duration.toMillis.toInt)

    def delayMillis(duration: Int): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[timers.SetTimeoutHandle] = js.undefined
        var isCancel                                         = false

        // TODO: we only actually cancel the last timeout. The check isCancel
        // makes sure that unsafeCancelled subscription is really respected.
        Cancelable.composite(
          Cancelable { () =>
            isCancel = true
            lastTimeout.foreach(timers.clearTimeout)
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                lastTimeout = timers.setTimeout(duration.toDouble) {
                  if (!isCancel) sink.unsafeOnNext(value)
                }
              },
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    def distinctBy[B](f: A => B)(implicit equality: Eq[B]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var lastValue: Option[B] = None

        source.unsafeSubscribe(
          Observer.createUnrecovered(
            { value =>
              val valueB     = f(value)
              val shouldSend = lastValue.forall(lastValue => !equality.eqv(lastValue, valueB))
              if (shouldSend) {
                lastValue = Some(valueB)
                sink.unsafeOnNext(value)
              }
            },
            sink.unsafeOnError,
          ),
        )
      }
    }

    @inline def distinct(implicit equality: Eq[A]): Observable[A] = distinctBy(identity)

    @inline def distinctByOnEquals[B](f: A => B): Observable[A] = distinctBy(f)(Eq.fromUniversalEquals)
    @inline def distinctOnEquals: Observable[A]                 = distinct(Eq.fromUniversalEquals)

    def withDefaultSubscription(sink: Observer[A]): Observable[A] = new Observable[A] {
      private var defaultSubscription = source.unsafeSubscribe(sink)

      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        // stop the default subscription.
        if (defaultSubscription != null) {
          defaultSubscription.unsafeCancel()
          defaultSubscription = null
        }

        source.unsafeSubscribe(sink)
      }
    }

    @inline def transformSource[B](transform: Observable[A] => Observable[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = transform(source).unsafeSubscribe(sink)
    }

    @inline def transformSink[B](transform: Observer[B] => Observer[A]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(transform(sink))
    }

    @inline def publish: Connectable[Observable[A]]                  = multicast(Subject.publish[A]())
    @deprecated("Use replayLatest instead", "0.3.4")
    @inline def replay: Connectable[Observable.MaybeValue[A]]        = replayLatest
    @inline def replayLatest: Connectable[Observable.MaybeValue[A]]    = multicastMaybeValue(Subject.replayLatest[A]())
    @inline def behavior(value: A): Connectable[Observable.Value[A]] = multicastValue(Subject.behavior(value))

    @inline def publishSelector[B](f: Observable[A] => Observable[B]): Observable[B]                  = transformSource(s => f(s.publish.refCount))
    @deprecated("Use replayLatestSelector instead", "0.3.4")
    @inline def replaySelector[B](f: Observable.MaybeValue[A] => Observable[B]): Observable[B]        = replayLatestSelector(f)
    @inline def replayLatestSelector[B](f: Observable.MaybeValue[A] => Observable[B]): Observable[B]    =
      transformSource(s => f(s.replayLatest.refCount))
    @inline def behaviorSelector[B](value: A)(f: Observable.Value[A] => Observable[B]): Observable[B] =
      transformSource(s => f(s.behavior(value).refCount))

    def multicast(pipe: Subject[A]): Connectable[Observable[A]] = Connectable(
      new Observable[A] {
        def unsafeSubscribe(sink: Observer[A]): Cancelable = Source[Observable].unsafeSubscribe(pipe)(sink)
      },
      () => source.unsafeSubscribe(pipe),
    )

    def multicastValue(pipe: Subject.Value[A]): Connectable[Observable.Value[A]] = Connectable(
      new Value[A] {
        def now(): A                                       = pipe.now()
        def unsafeSubscribe(sink: Observer[A]): Cancelable = pipe.unsafeSubscribe(sink)
      },
      () => source.unsafeSubscribe(pipe),
    )

    def multicastMaybeValue(pipe: Subject.MaybeValue[A]): Connectable[Observable.MaybeValue[A]] = Connectable(
      new MaybeValue[A] {
        def now(): Option[A]                               = pipe.now()
        def unsafeSubscribe(sink: Observer[A]): Cancelable = pipe.unsafeSubscribe(sink)
      },
      () => source.unsafeSubscribe(pipe),
    )

    @deprecated("Use prependEffect instead", "0.3.0")
    @inline def prependSync[F[_]: RunSyncEffect](value: F[A]): Observable[A] = prependEffect(value)
    @deprecated("Use prependEffect instead", "0.3.0")
    @inline def prependAsync[F[_]: RunEffect](value: F[A]): Observable[A]    = prependEffect(value)
    @inline def prependEffect[F[_]: RunEffect](value: F[A]): Observable[A]   = concatEffect[F, A](value, source)
    @inline def prependFuture(value: => Future[A]): Observable[A]            = concatFuture[A](value, source)

    def prepend(value: A): Observable[A]            = prependDelay(value)
    def prependDelay(value: => A): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        sink.unsafeOnNext(value)
        source.unsafeSubscribe(sink)
      }
    }

    def startWith(values: Iterable[A]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        values.foreach(sink.unsafeOnNext)
        source.unsafeSubscribe(sink)
      }
    }

    @deprecated("Use headEffect instead", "0.3.0")
    def headF[F[_]: Async]: F[A]      = headEffect[F]
    def headEffect[F[_]: Async]: F[A] = Async[F].async[A] { callback =>
      Async[F].delay {
        val cancelable = Cancelable.variable()
        var isDone     = false

        def dispatch(value: Either[Throwable, A]) = if (!isDone) {
          isDone = true
          cancelable.unsafeCancel()
          callback(value)
        }

        cancelable() = (() => source.unsafeSubscribe(Observer.create[A](value => dispatch(Right(value)), error => dispatch(Left(error)))))

        Some(Async[F].delay(cancelable.unsafeCancel()))
      }
    }

    @inline def headIO: IO[A] = headEffect[IO]

    @inline def head: Observable[A] = take(1)

    def take(num: Int): Observable[A] = {
      if (num <= 0) Observable.empty
      else
        new Observable[A] {
          def unsafeSubscribe(sink: Observer[A]): Cancelable = {
            var counter      = 0
            val subscription = Cancelable.variable()
            subscription() = (
                () =>
                  source.unsafeSubscribe(sink.contrafilter { _ =>
                    if (num > counter) {
                      counter += 1
                      true
                    } else {
                      subscription.unsafeCancel()
                      false
                    }
                  }),
            )

            subscription
          }
        }
    }

    def takeWhile(predicate: A => Boolean): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var finishedTake = false
        val subscription = Cancelable.variable()
        subscription() = (
            () =>
              source.unsafeSubscribe(sink.contrafilter { v =>
                if (finishedTake) false
                else if (predicate(v)) true
                else {
                  finishedTake = true
                  subscription.unsafeCancel()
                  false
                }
              }),
        )

        subscription
      }
    }

    def takeUntil(until: Observable[Unit]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var finishedTake = false
        val subscription = Cancelable.builder()

        subscription += (() =>
          until.unsafeSubscribe(
            Observer.createUnrecovered[Unit](
              { _ =>
                finishedTake = true
                subscription.unsafeCancel()
              },
              sink.unsafeOnError(_),
            ),
          ),
        )

        if (!finishedTake) subscription += (() => source.unsafeSubscribe(sink.contrafilter(_ => !finishedTake)))

        subscription
      }
    }

    def drop(num: Int): Observable[A] = {
      if (num <= 0) source
      else
        new Observable[A] {
          def unsafeSubscribe(sink: Observer[A]): Cancelable = {
            var counter = 0
            source.unsafeSubscribe(sink.contrafilter { _ =>
              if (num > counter) {
                counter += 1
                false
              } else true
            })
          }
        }
    }

    def dropWhile(predicate: A => Boolean): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var finishedDrop = false
        source.unsafeSubscribe(sink.contrafilter { v =>
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
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var finishedDrop = false

        val untilCancelable = Cancelable.variable()
        untilCancelable() = (
            () =>
              until.unsafeSubscribe(
                Observer.createUnrecovered[Unit](
                  { _ =>
                    finishedDrop = true
                    untilCancelable.unsafeCancel()
                  },
                  sink.unsafeOnError(_),
                ),
              ),
        )

        val subscription = source.unsafeSubscribe(sink.contrafilter(_ => finishedDrop))

        Cancelable.composite(subscription, untilCancelable)
      }
    }

    @inline def subscribeF[F[_]: Sync]: F[Cancelable] = Sync[F].delay(unsafeSubscribe())
    @inline def subscribeIO: IO[Cancelable]           = subscribeF[IO]
    @inline def subscribeSyncIO: SyncIO[Cancelable]   = subscribeF[SyncIO]

    @inline def unsafeSubscribe(): Cancelable           = source.unsafeSubscribe(Observer.empty)
    @inline def unsafeForeach(f: A => Unit): Cancelable = source.unsafeSubscribe(Observer.create(f))

    @deprecated("Use unsafeSubscribe(sink) or to(sink).unsafeSubscribe() or to(sink).subscribeF[F] instead", "0.3.0")
    @inline def subscribe(sink: Observer[A]): Cancelable = source.unsafeSubscribe(sink)
    @deprecated("Use unsafeSubscribe() or subscribeF[F] instead", "0.3.0")
    @inline def subscribe(): Cancelable                  = unsafeSubscribe()
    @deprecated("Use unsafeForeach(f) or foreach_(f).unsafeSubscribe() instead", "0.3.0")
    @inline def foreach(f: A => Unit): Cancelable        = unsafeForeach(f)
  }

  @inline implicit class IterableOperations[A](val source: Observable[Iterable[A]]) extends AnyVal {
    @inline def flattenIterable[B]: Observable[A] = source.mapIterable(identity)
  }

  @inline implicit class EitherOperations[A](val source: Observable[Either[Throwable, A]]) extends AnyVal {
    @inline def flattenEither[B]: Observable[A] = source.mapEither(identity)
  }

  @inline implicit class SubjectValueOperations[A](val handler: Subject.Value[A]) extends AnyVal {
    def lens[B](read: A => B)(write: (A, B) => A): Subject.Value[B] = new Observer[B] with Observable.Value[B] {
      @inline def now()                                          = read(handler.now())
      @inline def unsafeOnNext(value: B): Unit                   = handler.unsafeOnNext(write(handler.now(), value))
      @inline def unsafeOnError(error: Throwable): Unit          = handler.unsafeOnError(error)
      @inline def unsafeSubscribe(sink: Observer[B]): Cancelable = handler.map(read).unsafeSubscribe(sink)
    }
  }

  @inline implicit class SubjectMaybeValueOperations[A](val handler: Subject.MaybeValue[A]) extends AnyVal {
    def lens[B](seed: => A)(read: A => B)(write: (A, B) => A): Subject.MaybeValue[B] = new Observer[B] with Observable.MaybeValue[B] {
      @inline def now()                                          = handler.now().map(read)
      @inline def unsafeOnNext(value: B): Unit                   = handler.unsafeOnNext(write(handler.now().getOrElse(seed), value))
      @inline def unsafeOnError(error: Throwable): Unit          = handler.unsafeOnError(error)
      @inline def unsafeSubscribe(sink: Observer[B]): Cancelable = handler.map(read).unsafeSubscribe(sink)
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
      def unsafeSubscribe(sink: Observer[Seq[Subject.Value[A]]]): Cancelable = {
        handler.unsafeSubscribe(
          Observer.create(
            { sequence =>
              sink.unsafeOnNext(sequence.zipWithIndex.map { case (a, idx) =>
                new Observer[A] with Observable.Value[A] {
                  def now(): A = a

                  def unsafeSubscribe(sink: Observer[A]): Cancelable = {
                    sink.unsafeOnNext(a)
                    Cancelable.empty
                  }

                  def unsafeOnNext(value: A): Unit = {
                    handler.unsafeOnNext(sequence.updated(idx, value))
                  }

                  def unsafeOnError(error: Throwable): Unit = {
                    sink.unsafeOnError(error)
                  }
                }
              })
            },
            sink.unsafeOnError,
          ),
        )
      }
    }
  }
}
