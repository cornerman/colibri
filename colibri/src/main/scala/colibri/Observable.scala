package colibri

import colibri.effect._

import cats.{ MonoidK, Functor, FunctorFilter, Eq }
import cats.effect.{ Effect, IO }

import scala.scalajs.js
import scala.util.{ Success, Failure, Try }
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


  // Only one execution context in javascript that is a queued execution
  // context using the javascript event loop. We skip the implicit execution
  // context and just fire on the global one. As it is most likely what you
  // want to do in this API.
  import ExecutionContext.Implicits.global

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

  def fromTry[A](value: Try[A]): Observable[A] = new Observable[A] {
    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      value match {
        case Success(a) => Sink[G].onNext(sink)(a)
        case Failure(error) => Sink[G].onError(sink)(error)
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

  def fromFuture[A](future: Future[A]): Observable[A] = fromAsync(IO.fromFuture(IO.pure(future))(IO.contextShift(global)))

  def transformSink[F[_]: Source, A,B](source: F[A])(transform: Observer[_ >: B] => Observer[A]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(transform(Observer.lift(sink)))
  }

  def failed[S[_]: Source, A](source: S[A]): Observable[Throwable] = new Observable[Throwable] {
    def subscribe[G[_]: Sink](sink: G[_ >: Throwable]): Cancelable =
      Source[S].subscribe(source)(Observer.createUnhandled[A](_ => (), Sink[G].onError(sink)(_)))
  }

  @inline def interval(delay: FiniteDuration): Observable[Long] = intervalMillis(delay.toMillis.toInt)

  def intervalMillis(delay: Int): Observable[Long] = new Observable[Long] {
    def subscribe[G[_]: Sink](sink: G[_ >: Long]): Cancelable = {
      import org.scalajs.dom
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

  def concatFuture[T](values: Future[T]*): Observable[T] = fromIterable(values).concatMapFuture(identity)

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

  def concatFuture[T, S[_] : Source](value: Future[T], source: S[T]): Observable[T] = concatAsync(IO.fromFuture(IO.pure(value))(IO.contextShift(global)), source)

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

  def mapTry[F[_]: Source, A, B](source: F[A])(f: A => Try[B]): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = Source[F].subscribe(source)(Observer.createUnhandled[A](
      value => f(value) match {
        case Success(b) => Sink[G].onNext(sink)(b)
        case Failure(error) => Sink[G].onError(sink)(error)
      }
    ))
  }

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

  def scan[F[_]: Source, A, B](source: F[A])(seed: B)(f: (B, A) => B): Observable[B] = new Observable[B] {
    def subscribe[G[_]: Sink](sink: G[_ >: B]): Cancelable = {
      var state = seed

      Sink[G].onNext(sink)(seed)

      Source[F].subscribe(source)(Observer.contramap[G, B, A](sink) { value =>
        val result = f(state, value)
        state = result
        result
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

  @inline def concatMapFuture[S[_]: Source, A, B](source: S[A])(f: A => Future[B]): Observable[B] = concatMapAsync(source)(v => IO.fromFuture(IO.pure(f(v)))(IO.contextShift(global)))

  @inline def mapSync[S[_]: Source, F[_]: RunSyncEffect, A, B](source: S[A])(f: A => F[B]): Observable[B] = map(source)(v => RunSyncEffect[F].unsafeRun(f(v)))

  @inline def combineLatest[SA[_]: Source, SB[_]: Source, A, B](sourceA: SA[A])(sourceB: SB[B]): Observable[(A,B)] = combineLatestMap(sourceA)(sourceB)(_ -> _)

  def combineLatestMap[SA[_]: Source, SB[_]: Source, A, B, R](sourceA: SA[A])(sourceB: SB[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
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

  def withLatestFrom[SA[_]: Source, SB[_]: Source, A, B, R](source: SA[A])(latest: SB[B])(f: (A, B) => R): Observable[R] = new Observable[R] {
    def subscribe[G[_]: Sink](sink: G[_ >: R]): Cancelable = {
      var latestValue: Option[B] = None

      Cancelable.composite(
        Source[SA].subscribe(source)(Observer.create[A](
          value => latestValue.foreach(latestValue => Sink[G].onNext(sink)(f(value, latestValue))),
          Sink[G].onError(sink),
        )),
        Source[SB].subscribe(latest)(Observer.createUnhandled[B](
          value => latestValue = Some(value),
          Sink[G].onError(sink),
        ))
      )
    }
  }

  def zipWithIndex[S[_]: Source, A, R](source: S[A]): Observable[(A, Int)] = new Observable[(A, Int)] {
    def subscribe[G[_]: Sink](sink: G[_ >: (A, Int)]): Cancelable = {
      var counter = 0

      Source[S].subscribe(source)(Observer.createUnhandled[A](
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
      import org.scalajs.dom
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
      import org.scalajs.dom
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
        Source[S].subscribe(source)(Observer.createUnhandled[A](
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
      import org.scalajs.dom
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

      Source[S].subscribe(source)(Observer.createUnhandled[A](
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

  def withDefaultCancelable[S[_]: Source, F[_]: Sink, A](source: S[A])(sink: F[A]): Observable[A] = new Observable[A] {
    private var defaultCancelable = Source[S].subscribe(source)(sink)

    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      // stop the default subscription.
      if (defaultCancelable != null) {
        defaultCancelable.cancel()
        defaultCancelable = null
      }

      Source[S].subscribe(source)(sink)
    }
  }

  @inline def share[F[_]: Source, A](source: F[A]): Observable[A] = pipeThrough[F, A, Lambda[X => Subject[X, X]]](source)(Subject.publish[A])
  @inline def shareWithLatest[F[_]: Source, A](source: F[A]): Observable[A] = pipeThrough[F, A, Lambda[X => Subject[X, X]]](source)(Subject.behavior[A])
  @inline def shareWithLatestAndSeed[F[_]: Source, A](source: F[A])(seed: A): Observable[A] = pipeThrough[F, A, Lambda[X => Subject[X, X]]](source)(Subject.behavior[A](seed))

  def pipeThrough[F[_]: Source, A, S[_] : Source : Sink](source: F[A])(pipe: S[A]): Observable[A] = new Observable[A] {
    private var subscribers = 0
    private var currentCancelable: Cancelable = null

    def subscribe[G[_]: Sink](sink: G[_ >: A]): Cancelable = {
      subscribers += 1
      val subscription = Source[S].subscribe(pipe)(sink)

      if (currentCancelable == null) {
        val variable = Cancelable.variable()
        currentCancelable = variable
        variable() = Source[F].subscribe(source)(pipe)
      }

      Cancelable { () =>
        subscription.cancel()
        subscribers -= 1
        if (subscribers == 0) {
          currentCancelable.cancel()
          currentCancelable = null
        }
      }
    }
  }

  @inline def prependSync[S[_]: Source, A, F[_] : RunSyncEffect](source: S[A])(value: F[A]): Observable[A] = concatSync[F, A, S](value, source)
  @inline def prependAsync[S[_]: Source, A, F[_] : Effect](source: S[A])(value: F[A]): Observable[A] = concatAsync[F, A, S](value, source)
  @inline def prependFuture[S[_]: Source, A](source: S[A])(value: Future[A]): Observable[A] = concatFuture[A, S](value, source)

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

      subscription += Source[FU].subscribe(until)(Observer.createUnhandled[Unit](
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
      untilCancelable() = Source[FU].subscribe(until)(Observer.createUnhandled[Unit](
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
    @inline def combineLatest[S[_]: Source, B, R](combined: S[B]): Observable[(A,B)] = Observable.combineLatest(source)(combined)
    @inline def combineLatestMap[S[_]: Source, B, R](combined: S[B])(f: (A, B) => R): Observable[R] = Observable.combineLatestMap(source)(combined)(f)
    @inline def withLatestFrom[S[_]: Source, B, R](latest: S[B])(f: (A, B) => R): Observable[R] = Observable.withLatestFrom(source)(latest)(f)
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
    @inline def concatMapFuture[B](f: A => Future[B]): Observable[B] = Observable.concatMapFuture(source)(f)
    @inline def concatMapAsync[G[_]: Effect, B](f: A => G[B]): Observable[B] = Observable.concatMapAsync(source)(f)
    @inline def mapSync[G[_]: RunSyncEffect, B](f: A => G[B]): Observable[B] = Observable.mapSync(source)(f)
    @inline def map[B](f: A => B): Observable[B] = Observable.map(source)(f)
    @inline def mapTry[B](f: A => Try[B]): Observable[B] = Observable.mapTry(source)(f)
    @inline def mapFilter[B](f: A => Option[B]): Observable[B] = Observable.mapFilter(source)(f)
    @inline def collect[B](f: PartialFunction[A, B]): Observable[B] = Observable.collect(source)(f)
    @inline def filter(f: A => Boolean): Observable[A] = Observable.filter(source)(f)
    @inline def scan[B](seed: B)(f: (B, A) => B): Observable[B] = Observable.scan(source)(seed)(f)
    @inline def recover(f: PartialFunction[Throwable, A]): Observable[A] = Observable.recover(source)(f)
    @inline def recoverOption(f: PartialFunction[Throwable, Option[A]]): Observable[A] = Observable.recoverOption(source)(f)
    @inline def share: Observable[A] = Observable.share(source)
    @inline def shareWithLatest: Observable[A] = Observable.shareWithLatest(source)
    @inline def shareWithLatestAndSeed(seed: A): Observable[A] = Observable.shareWithLatestAndSeed(source)(seed)
    @inline def prepend(value: A): Observable[A] = Observable.prepend(source)(value)
    @inline def prependSync[F[_] : RunSyncEffect](value: F[A]): Observable[A] = Observable.prependSync(source)(value)
    @inline def prependAsync[F[_] : Effect](value: F[A]): Observable[A] = Observable.prependAsync(source)(value)
    @inline def prependFuture(value: Future[A]): Observable[A] = Observable.prependFuture(source)(value)
    @inline def startWith(values: Iterable[A]): Observable[A] = Observable.startWith(source)(values)
    @inline def head: Observable[A] = Observable.head(source)
    @inline def take(num: Int): Observable[A] = Observable.take(source)(num)
    @inline def takeWhile(predicate: A => Boolean): Observable[A] = Observable.takeWhile(source)(predicate)
    @inline def takeUntil[F[_]: Source](until: F[Unit]): Observable[A] = Observable.takeUntil(source)(until)
    @inline def drop(num: Int): Observable[A] = Observable.drop(source)(num)
    @inline def dropWhile(predicate: A => Boolean): Observable[A] = Observable.dropWhile(source)(predicate)
    @inline def dropUntil[F[_]: Source](until: F[Unit]): Observable[A] = Observable.dropUntil(source)(until)
    @inline def withDefaultCancelable[G[_] : Sink](sink: G[A]): Observable[A] = Observable.withDefaultCancelable(source)(sink)
    @inline def subscribe(): Cancelable = source.subscribe(Observer.empty)
    @inline def foreach(f: A => Unit): Cancelable = source.subscribe(Observer.create(f))
  }

  private def recovered[T](action: => Unit, onError: Throwable => Unit) = try action catch { case NonFatal(t) => onError(t) }
}
