package colibri

import cats._
import cats.implicits._
import colibri.effect.{RunSyncEffect, RunEffect}
import cats.effect.{Sync, SyncIO, Async, IO, Resource}

import scala.scalajs.js
import scala.scalajs.js.timers
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait Observable[+A] {
  def unsafeSubscribe(sink: Observer[A]): Cancelable
}
object Observable    {
  import org.scalajs.macrotaskexecutor.MacrotaskExecutor
  private val MicrotaskExecutor = scala.scalajs.concurrent.QueueExecutionContext.promises()

  implicit object source extends Source[Observable] {
    @inline def unsafeSubscribe[A](source: Observable[A])(sink: Observer[A]): Cancelable = source.unsafeSubscribe(sink)
  }

  implicit object liftSource extends LiftSource[Observable] {
    @inline def lift[H[_]: Source, A](source: H[A]): Observable[A] = Observable.lift[H, A](source)
  }

  implicit object catsInstances
      extends MonadError[Observable, Throwable]
      with FunctorFilter[Observable]
      with Alternative[Observable]
      with CoflatMap[Observable] {
    @inline override def unit: Observable[Unit]                                                                        = Observable.unit
    @inline override def pure[A](a: A): Observable[A]                                                                  = Observable.pure(a)
    @inline override def map[A, B](fa: Observable[A])(f: A => B): Observable[B]                                        = fa.map(f)
    @inline override def handleErrorWith[A](fa: Observable[A])(f: Throwable => Observable[A]): Observable[A]           =
      fa.attempt.flatMap(_.fold(f, Observable.pure))
    @inline override def raiseError[A](e: Throwable): Observable[A]                                                    = Observable.raiseError(e)
    @inline override def recover[A](fa: Observable[A])(pf: PartialFunction[Throwable, A]): Observable[A]               = fa.recover(pf)
    @inline override def flatMap[A, B](fa: Observable[A])(f: A => Observable[B]): Observable[B]                        = fa.flatMap(f)
    @inline override def flatten[A](ffa: Observable[Observable[A]]): Observable[A]                                     = ffa.flatten
    @inline override def tailRecM[A, B](a: A)(f: A => Observable[Either[A, B]]): Observable[B]                         = Observable.tailRecM[A, B](a)(f)
    @inline override def ensure[A](fa: Observable[A])(error: => Throwable)(predicate: A => Boolean): Observable[A]     =
      fa.mapEither(a => if (predicate(a)) Right(a) else Left(error))
    @inline override def ensureOr[A](fa: Observable[A])(error: A => Throwable)(predicate: A => Boolean): Observable[A] =
      fa.mapEither(a => if (predicate(a)) Right(a) else Left(error(a)))
    @inline override def rethrow[A, EE <: Throwable](fa: Observable[Either[EE, A]]): Observable[A]                     = fa.flattenEither
    @inline override def adaptError[A](fa: Observable[A])(pf: PartialFunction[Throwable, Throwable]): Observable[A]    = fa.adaptError(pf)

    @inline def coflatMap[A, B](fa: Observable[A])(f: Observable[A] => B): Observable[B] = Observable.eval(f(fa))

    @inline override def functor                                                                   = this
    @inline override def mapFilter[A, B](fa: Observable[A])(f: A => Option[B]): Observable[B]      = fa.mapFilter(f)
    @inline override def collect[A, B](fa: Observable[A])(f: PartialFunction[A, B]): Observable[B] = fa.collect(f)
    @inline override def filter[A](fa: Observable[A])(f: A => Boolean): Observable[A]              = fa.filter(f)
    @inline override def empty[T]                                                                  = Observable.empty
    @inline override def combineK[T](a: Observable[T], b: Observable[T])                           = Observable.concat(a, b)
  }

  implicit object catsParallelCombine extends Parallel[Observable] {
    import CombineObservable.{apply => wrap, unwrap}

    override type F[A] = CombineObservable.Type[A]

    override def monad: Monad[Observable]                         = implicitly[Monad[Observable]]
    override def applicative: Applicative[CombineObservable.Type] = implicitly[Applicative[CombineObservable.Type]]

    override val sequential = new (CombineObservable.Type ~> Observable) {
      def apply[A](fa: CombineObservable.Type[A]): Observable[A] = unwrap(fa)
    }
    override val parallel   = new (Observable ~> CombineObservable.Type) {
      def apply[A](fa: Observable[A]): CombineObservable.Type[A] = wrap(fa)
    }
  }

  implicit object catsInstancesSubject extends Invariant[Subject] {
    @inline def imap[A, B](fa: Subject[A])(f: A => B)(g: B => A): Subject[B] = fa.imapProSubject(g)(f)
  }

  implicit object catsInstancesProSubject extends arrow.Profunctor[ProSubject] {
    def dimap[A, B, C, D](fab: ProSubject[A, B])(f: C => A)(g: B => D): ProSubject[C, D] = fab.imapProSubject(f)(g)
  }

  trait Value[+A]      extends Observable[A] {
    def now(): A
  }
  trait MaybeValue[+A] extends Observable[A] {
    def now(): Option[A]
  }

  trait HasCancelable {
    def cancelable: Cancelable
  }

  trait Hot[+A]           extends Observable[A] with HasCancelable
  trait HotValue[+A]      extends Value[A] with HasCancelable
  trait HotMaybeValue[+A] extends MaybeValue[A] with HasCancelable

  object Empty extends Observable[Nothing] {
    @inline def unsafeSubscribe(sink: Observer[Nothing]): Cancelable = Cancelable.empty
  }

  @inline def empty[A]: Observable[A] = Empty
  val unit: Observable[Unit]          = Observable.pure(())

  def pure[T](value: T): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      sink.unsafeOnNext(value)
      Cancelable.empty
    }
  }

  @inline def apply[T](value: T): Observable[T] = pure(value)

  @inline def apply[T](value: T, value2: T, values: T*): Observable[T] = Observable.fromIterable(value +: value2 +: values)

  def eval[T](value: => T): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      sink.unsafeOnNext(value)
      Cancelable.empty
    }
  }

  def evalObservable[T](value: => Observable[T]): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = value.unsafeSubscribe(sink)
  }

  @deprecated("Use Observable.raiseError instead", "0.3.0")
  def failure[T](error: Throwable): Observable[T]    = raiseError(error)
  def raiseError[T](error: Throwable): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      sink.unsafeOnError(error)
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

  def createEmpty(subscription: () => Cancelable): Observable[Nothing] = create(_ => subscription())

  def fromIterable[T](values: Iterable[T]): Observable[T] = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      values.foreach(sink.unsafeOnNext)
      Cancelable.empty
    }
  }

  def fromEval[A](evalA: Eval[A]): Observable[A] = eval(evalA.value)

  def fromTry[A](value: Try[A]): Observable[A] = fromEither(value.toEither)

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

  def fromResource[F[_]: RunEffect: Sync, A](resource: Resource[F, A]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      val cancelable = Cancelable.variable()

      val cancelRun = RunEffect[F].unsafeRunSyncOrAsyncCancelable(resource.allocated) {
        case Right((value, finalizer)) =>
          sink.unsafeOnNext(value)

          cancelable.unsafeAddExisting(Cancelable { () =>
            // async and forget the finalizer, we do not need to cancel it.
            // just pass the error to the sink.
            val _ = RunEffect[F].unsafeRunSyncOrAsyncCancelable(finalizer) {
              case Right(())   => ()
              case Left(error) => sink.unsafeOnError(error)
            }
          })
          cancelable.unsafeFreeze()

        case Left(error) =>
          sink.unsafeOnError(error)
      }

      Cancelable.composite(cancelRun, cancelable)
    }
  }

  def like[H[_]: ObservableLike, A](observableLike: H[A]): Observable[A] = ObservableLike[H].toObservable(observableLike)

  @deprecated("Use concatEffect instead", "0.3.0")
  def concatSync[F[_]: RunSyncEffect, T](effects: F[T]*): Observable[T] = concatEffect(effects: _*)
  @deprecated("Use concatEffect instead", "0.3.0")
  def concatAsync[F[_]: RunEffect, T](effects: F[T]*): Observable[T]    = concatEffect(effects: _*)

  def concatEffect[F[_]: RunEffect, T](effects: F[T]*): Observable[T] = fromIterable(effects).mapEffect(identity)

  def concatFuture[T](value1: => Future[T]): Observable[T]                                                                   = concatEffect(IO.fromFuture(IO(value1)))
  def concatFuture[T](value1: => Future[T], value2: => Future[T]): Observable[T]                                             =
    concatEffect(IO.fromFuture(IO(value1)), IO.fromFuture(IO(value2)))
  def concatFuture[T](value1: => Future[T], value2: => Future[T], value3: => Future[T]): Observable[T]                       =
    concatEffect(IO.fromFuture(IO(value1)), IO.fromFuture(IO(value2)), IO.fromFuture(IO(value3)))
  def concatFuture[T](value1: => Future[T], value2: => Future[T], value3: => Future[T], value4: => Future[T]): Observable[T] =
    concatEffect(IO.fromFuture(IO(value1)), IO.fromFuture(IO(value2)), IO.fromFuture(IO(value3)), IO.fromFuture(IO(value4)))
  def concatFuture[T](
      value1: => Future[T],
      value2: => Future[T],
      value3: => Future[T],
      value4: => Future[T],
      value5: => Future[T],
  ): Observable[T] = concatEffect(
    IO.fromFuture(IO(value1)),
    IO.fromFuture(IO(value2)),
    IO.fromFuture(IO(value3)),
    IO.fromFuture(IO(value4)),
    IO.fromFuture(IO(value5)),
  )

  @deprecated("Use concatEffect instead", "0.3.0")
  def concatSync[F[_]: RunSyncEffect, T](effect: F[T], source: Observable[T]): Observable[T] = concatEffect(effect, source)
  @deprecated("Use concatEffect instead", "0.3.0")
  def concatAsync[F[_]: RunEffect, T](effect: F[T], source: Observable[T]): Observable[T]    = concatEffect(effect, source)
  def concatEffect[F[_]: RunEffect, T](effect: F[T], source: Observable[T]): Observable[T]   = new Observable[T] {
    def unsafeSubscribe(sink: Observer[T]): Cancelable = {
      val consecutive = Cancelable.consecutive()
      consecutive.unsafeAdd(() =>
        RunEffect[F].unsafeRunSyncOrAsyncCancelable[T](effect) { either =>
          either.fold(sink.unsafeOnError, sink.unsafeOnNext)
          consecutive.switch()
        },
      )
      consecutive.unsafeAdd(() => source.unsafeSubscribe(sink))
      consecutive.unsafeFreeze()
      consecutive
    }
  }
  def concatFuture[T](value: => Future[T], source: Observable[T]): Observable[T]             =
    concatEffect(IO.fromFuture(IO.pure(value)), source)

  @inline def merge[A](sources: Observable[A]*): Observable[A] = mergeIterable(sources)

  @deprecated("Use mergeIterable instead", "0.4.5")
  def mergeSeq[A](sources: Seq[Observable[A]]): Observable[A]           = mergeIterable(sources)
  def mergeIterable[A](sources: Iterable[Observable[A]]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      val subscriptions = sources.map { source =>
        source.unsafeSubscribe(sink)
      }

      Cancelable.compositeFromIterable(subscriptions)
    }
  }

  @inline def switch[A](sources: Observable[A]*): Observable[A] = switchIterable(sources)

  @deprecated("Use switchIterable instead", "0.4.5")
  def switchSeq[A](sources: Seq[Observable[A]]): Observable[A]           = switchIterable(sources)
  def switchIterable[A](sources: Iterable[Observable[A]]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      val variable = Cancelable.variable()
      sources.foreach { source =>
        variable.unsafeAdd(() => source.unsafeSubscribe(sink))
      }
      variable.unsafeFreeze()

      variable
    }
  }

  @inline def concat[A](sources: Observable[A]*): Observable[A] = concatIterable(sources)

  @deprecated("Use concatIterable instead", "0.4.5")
  def concatSeq[A](sources: Seq[Observable[A]]): Observable[A]           = concatIterable(sources)
  def concatIterable[A](sources: Iterable[Observable[A]]): Observable[A] = new Observable[A] {
    def unsafeSubscribe(sink: Observer[A]): Cancelable = {
      val consecutive = Cancelable.consecutive()

      var innerCancelCheck      = false
      var innerCheckIsScheduled = false

      sources.foreach { source =>
        consecutive.unsafeAdd { () =>
          var cancelable: Cancelable = null
          cancelable = source.unsafeSubscribe(
            Observer.create[A](
              { a =>
                sink.unsafeOnNext(a)
                if (!innerCheckIsScheduled) {
                  innerCheckIsScheduled = true
                  innerCancelCheck = false
                  MicrotaskExecutor.execute { () =>
                    innerCheckIsScheduled = false
                    if (!innerCancelCheck && cancelable.isEmpty()) consecutive.switch()
                  }
                }
              },
              sink.unsafeOnError,
            ),
          )

          if (cancelable.isEmpty()) {
            innerCancelCheck = true
            consecutive.switch()
          }
          cancelable
        }
      }
      consecutive.unsafeFreeze()

      consecutive
    }
  }

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

  def tailRecM[A, B](a: A)(f: A => Observable[Either[A, B]]): Observable[B] = new Observable[B] {
    def unsafeSubscribe(sink: Observer[B]): Cancelable = {
      val subjectRecurse = Subject.publish[Observable[Either[A, B]]]()
      val consecutive    = Cancelable.consecutive()

      var openSubscriptions     = 0
      var innerCheckIsScheduled = false

      var subscription: Cancelable = null
      subscription = subjectRecurse
        .unsafeSubscribe(
          Observer.create[Observable[Either[A, B]]](
            { source =>
              openSubscriptions += 1
              consecutive.unsafeAdd { () =>
                openSubscriptions -= 1
                var cancelable: Cancelable = null
                cancelable = source.unsafeSubscribe(
                  Observer.create[Either[A, B]](
                    { either =>
                      either match {
                        case Right(b) => sink.unsafeOnNext(b)
                        case Left(a)  => subjectRecurse.unsafeOnNext(f(a))
                      }

                      if (!innerCheckIsScheduled) {
                        innerCheckIsScheduled = true
                        MicrotaskExecutor.execute { () =>
                          innerCheckIsScheduled = false
                          if (cancelable.isEmpty()) {
                            if (openSubscriptions == 0) consecutive.unsafeFreeze()
                            else consecutive.switch()
                          }
                        }
                      }
                    },
                    sink.unsafeOnError,
                  ),
                )

                cancelable
              }
            },
            sink.unsafeOnError,
          ),
        )

      subjectRecurse.unsafeOnNext(f(a))

      Cancelable.composite(consecutive, Cancelable.withIsEmptyWrap(consecutive.isEmpty())(subscription))
    }

  }

  @inline implicit class Operations[A](private val source: Observable[A]) extends AnyVal {

    def liftSource[H[_]: LiftSource]: H[A] = LiftSource[H].lift(source)

    def failed: Observable[Throwable] = new Observable[Throwable] {
      def unsafeSubscribe(sink: Observer[Throwable]): Cancelable = source.unsafeSubscribe(sink.failed)
    }

    def dropFailed: Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = source.unsafeSubscribe(sink.dropOnError)
    }

    def debugLog: Observable[A]                 = via(Observer.debugLog)
    def debugLog(prefix: String): Observable[A] = via(Observer.debugLog(prefix))

    def via(sink: Observer[A]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink2: Observer[A]): Cancelable = source.unsafeSubscribe(Observer.combine(sink, sink2))
    }

    // TODO: this will become the normal foreach after the deprecated gready foreach is removed.
    def foreach_(f: A => Unit): Observable[Unit] = to(Observer.create(f))

    def to(sink: Observer[A]): Observable[Unit] = new Observable[Unit] {
      def unsafeSubscribe(sink2: Observer[Unit]): Cancelable = source.unsafeSubscribe(Observer.combine(sink, sink2.void))
    }

    def subscribing[B](f: Observable[B]): Observable[A] = tapSubscribe(() => f.unsafeSubscribe())

    def map[B](f: A => B): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = source.unsafeSubscribe(sink.contramap(f))
    }

    def parMapEffect[B, F[_]: RunEffect](f: A => F[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val tasks = js.Array[Either[Throwable, B]]()

        val taskCancelables = Cancelable.builder()

        val cancelable = source.unsafeSubscribe(
          Observer.create(
            { input =>
              val index = tasks.length
              tasks.push(null)

              val effect = f(input)

              taskCancelables.unsafeAdd(() =>
                RunEffect[F].unsafeRunSyncOrAsyncCancelable(effect) { either =>
                  tasks(index) = either
                  val finishedTasks = tasks.takeWhileInPlace(_ != null)
                  finishedTasks.foreach {
                    case Right(value) => sink.unsafeOnNext(value)
                    case Left(error)  => sink.unsafeOnError(error)
                  }
                },
              )
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(cancelable, taskCancelables)
      }
    }

    def parMapFuture[B](f: A => Future[B]): Observable[B] = parMapEffect(a => IO.fromFuture(IO(f(a))))

    def discard: Observable[Nothing]                             = Observable.empty.subscribing(source)
    def void: Observable[Unit]                                   = map(_ => ())
    def as[B](value: B): Observable[B]                           = map(_ => value)
    def asEval[B](value: => B): Observable[B]                    = map(_ => value)
    def asEffect[F[_]: RunEffect, B](value: F[B]): Observable[B] = mapEffect(_ => value)
    def asFuture[B](value: => Future[B]): Observable[B]          = mapFuture(_ => value)

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

    def switchScan[B](seed: B)(f: (B, A) => Observable[B]): Observable[B] = new Observable[B] {
      override def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        var current = seed
        val cancel  = Cancelable.variable()

        def recurse(nested: Observable[B], theA: A): Cancelable = nested.unsafeSubscribe(
          Observer.create[B](
            { value =>
              current = value
              val result = f(current, theA)
              sink.unsafeOnNext(value)
              cancel.unsafeAdd(() => recurse(result, theA))
            },
            sink.unsafeOnError,
          ),
        )

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val next = f(current, value)
              cancel.unsafeAdd(() => recurse(next, value))
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(
          subscription,
          Cancelable.checkIsEmpty(subscription.isEmpty())(cancel.unsafeFreeze),
          cancel,
        )
      }
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

    def recoverMap(f: Throwable => A): Observable[A] = recover { case t => f(t) }

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

    def adaptError(f: PartialFunction[Throwable, Throwable]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable =
        source.unsafeSubscribe(sink.doOnError { error =>
          sink.unsafeOnError(f.applyOrElse[Throwable, Throwable](error, t => t))
        })
    }

    def tapSubscribeEffect[F[_]: RunEffect](f: F[Cancelable]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        val variable = Cancelable.variable()

        val cancelable = RunEffect[F].unsafeRunSyncOrAsyncCancelable[Cancelable](f) {
          case Right(value) =>
            variable.unsafeAddExisting(value)
            variable.unsafeFreeze()
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

    def tapCancel(f: () => Unit): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        Cancelable.composite(
          source.unsafeSubscribe(sink),
          Cancelable.ignoreIsEmpty { () =>
            f()
          },
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

    def merge(sources: Observable[A]*): Observable[A] = Observable.mergeIterable(source +: sources)

    def switch(sources: Observable[A]*): Observable[A] = Observable.switchIterable(source +: sources)

    def concat(sources: Observable[A]*): Observable[A] = Observable.concatIterable(source +: sources)

    @inline def mergeMap[B](f: A => Observable[B]): Observable[B] = mapObservableWithCancelable(f)(Cancelable.builder)
    @inline def mergeMapEffect[F[_] : RunEffect, B](f: A => F[B]): Observable[B] = mergeMap(a => Observable.fromEffect(f(a)))
    @inline def mergeMapFuture[B](f: A => Future[B]): Observable[B] = mergeMap(a => Observable.fromFuture(f(a)))

    @inline def switchMap[B](f: A => Observable[B]): Observable[B] = mapObservableWithCancelable(f)(Cancelable.variable)
    @inline def switchMapEffect[F[_] : RunEffect, B](f: A => F[B]): Observable[B] = switchMap(a => Observable.fromEffect(f(a)))
    @inline def switchMapFuture[B](f: A => Future[B]): Observable[B] = switchMap(a => Observable.fromFuture(f(a)))

    private def mapObservableWithCancelable[B](f: A => Observable[B])(newCancelableSetter: () => Cancelable.Setter): Observable[B] =
      new Observable[B] {
        def unsafeSubscribe(sink: Observer[B]): Cancelable = {
          val setter = newCancelableSetter()

          val subscription = source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                val sourceB = f(value)
                setter.unsafeAdd(() => sourceB.unsafeSubscribe(sink))
              },
              sink.unsafeOnError,
            ),
          )

          Cancelable.composite(
            subscription,
            Cancelable.checkIsEmpty(subscription.isEmpty())(setter.unsafeFreeze),
            setter,
          )
        }
      }

    @deprecated("Use mapEffect instead", "0.3.0")
    @inline def mapSync[F[_]: RunSyncEffect, B](f: A => F[B]): Observable[B] = mapEffect(f)
    @deprecated("Use mapEffect instead", "0.3.0")
    def mapAsync[F[_]: RunEffect, B](f: A => F[B]): Observable[B]            = mapEffect(f)

    def mapEffect[F[_]: RunEffect, B](f: A => F[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val consecutive = Cancelable.consecutive()

        var isRunOpen = false

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val effect = f(value)
              consecutive.unsafeAdd { () =>
                isRunOpen = true
                RunEffect[F].unsafeRunSyncOrAsyncCancelable[B](effect) { either =>
                  isRunOpen = false
                  either.fold(sink.unsafeOnError, sink.unsafeOnNext)
                  consecutive.switch()
                }
              }
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(
          subscription,
          Cancelable.withIsEmptyWrap(subscription.isEmpty() && !isRunOpen)(consecutive),
        )
      }
    }

    @inline def mapFuture[B](f: A => Future[B]): Observable[B] = mapEffect(v => IO.fromFuture(IO(f(v))))

    @inline def mapResource[F[_]: RunEffect: Sync, B](f: A => Resource[F, B]): Observable[B] =
      mapResourceWithCancelable(f)(Cancelable.builder)

    @inline def switchMapResource[F[_]: RunEffect: Sync, B](f: A => Resource[F, B]): Observable[B] =
      mapResourceWithCancelable(f)(Cancelable.variable)

    private def mapResourceWithCancelable[F[_]: RunEffect: Sync, B](
        f: A => Resource[F, B],
    )(newCancelableSetter: () => Cancelable.Setter): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val consecutive      = Cancelable.consecutive()
        val cancelableSetter = newCancelableSetter()

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val resource = f(value)
              consecutive.unsafeAdd(() =>
                RunEffect[F].unsafeRunSyncOrAsyncCancelable(resource.allocated) { either =>
                  either match {
                    case Right((value, finalizer)) =>
                      sink.unsafeOnNext(value)

                      cancelableSetter.unsafeAddExisting(Cancelable { () =>
                        // async and forget the finalizer, we do not need to cancel it.
                        // just pass the error to the sink.
                        val _ = RunEffect[F].unsafeRunSyncOrAsyncCancelable(finalizer) {
                          case Right(())   => ()
                          case Left(error) => sink.unsafeOnError(error)
                        }
                      })

                    case Left(error) =>
                      sink.unsafeOnError(error)
                  }

                  consecutive.switch()
                },
              )
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(subscription, consecutive, cancelableSetter)
      }
    }

    @deprecated("Use mapEffectSingleOrDrop instead", "0.3.0")
    def mapAsyncSingleOrDrop[F[_]: RunEffect, B](f: A => F[B]): Observable[B] = mapEffectSingleOrDrop(f)

    def mapEffectSingleOrDrop[F[_]: RunEffect, B](f: A => F[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val single = Cancelable.singleOrDrop()

        var isRunOpen = false

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val effect = f(value)
              single.unsafeAdd { () =>
                isRunOpen = true
                RunEffect[F].unsafeRunSyncOrAsyncCancelable[B](effect) { either =>
                  isRunOpen = false
                  either.fold(sink.unsafeOnError, sink.unsafeOnNext)
                  single.done()
                }
              }
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(
          subscription,
          Cancelable.withIsEmptyWrap(subscription.isEmpty() && !isRunOpen)(single),
        )
      }
    }

    @inline def mapFutureSingleOrDrop[B](f: A => Future[B]): Observable[B] =
      mapEffectSingleOrDrop(v => IO.fromFuture(IO(f(v))))

    @inline def flatMap[B](f: A => Observable[B]): Observable[B] = concatMap(f)

    def concatMap[B](f: A => Observable[B]): Observable[B] = new Observable[B] {
      def unsafeSubscribe(sink: Observer[B]): Cancelable = {
        val consecutive = Cancelable.consecutive()

        var innerCancelCheck      = false
        var innerCheckIsScheduled = false

        val subscription = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              val source = f(value)

              consecutive.unsafeAdd { () =>
                var cancelable: Cancelable = null
                cancelable = source.unsafeSubscribe(
                  Observer.create[B](
                    { b =>
                      sink.unsafeOnNext(b)
                      if (!innerCheckIsScheduled) {
                        innerCheckIsScheduled = true
                        innerCancelCheck = false
                        MicrotaskExecutor.execute { () =>
                          innerCheckIsScheduled = false
                          if (!innerCancelCheck && cancelable.isEmpty()) consecutive.switch()
                        }
                      }
                    },
                    sink.unsafeOnError,
                  ),
                )

                if (cancelable.isEmpty()) {
                  innerCancelCheck = true
                  consecutive.switch()
                }
                cancelable
              }
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(
          subscription,
          Cancelable.checkIsEmpty(subscription.isEmpty())(consecutive.unsafeFreeze),
          consecutive,
        )
      }
    }

    def dropSyncAll: Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var isSync = true

        val cancelable = source.unsafeSubscribe(
          Observer.create[A](
            value => if (!isSync) sink.unsafeOnNext(value),
            sink.unsafeOnError,
          ),
        )

        isSync = false

        cancelable
      }
    }

    def dropUntilSyncLatest: Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var isSync        = true
        var lastSyncValue = Option.empty[A]

        val cancelable = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              if (isSync) {
                lastSyncValue = Some(value)
              } else {
                sink.unsafeOnNext(value)
              }
            },
            sink.unsafeOnError,
          ),
        )

        isSync = false
        lastSyncValue.foreach(sink.unsafeOnNext)
        cancelable
      }
    }

    def dropSyncGlitches: Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var isCancel       = false
        var runIsScheduled = false
        var lastValue      = Option.empty[A]

        val cancelable = source.unsafeSubscribe(
          Observer.create[A](
            { value =>
              lastValue = Some(value)
              if (!runIsScheduled) {
                runIsScheduled = true
                MicrotaskExecutor.execute { () =>
                  runIsScheduled = false
                  if (!isCancel) lastValue.foreach(sink.unsafeOnNext)
                  lastValue = None
                }
              }
            },
            sink.unsafeOnError,
          ),
        )

        Cancelable.composite(
          Cancelable.withIsEmpty(!runIsScheduled) { () =>
            isCancel = true
          },
          cancelable,
        )
      }
    }

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
        var openRuns                                         = 0

        Cancelable.composite(
          Cancelable.withIsEmpty(openRuns == 0) { () =>
            isCancel = true
            lastTimeout.foreach(timers.clearTimeout)
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                lastTimeout.foreach { id =>
                  timers.clearTimeout(id)
                }
                openRuns += 1
                lastTimeout = timers.setTimeout(duration.toDouble) {
                  openRuns -= 1
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

    def evalOn(ec: ExecutionContext): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var isCancel = false
        var openRuns = 0

        Cancelable.composite(
          Cancelable.withIsEmpty(openRuns == 0) { () =>
            isCancel = true
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                openRuns += 1
                ec.execute { () =>
                  openRuns -= 1
                  if (!isCancel) sink.unsafeOnNext(value)
                }
              },
              sink.unsafeOnError,
            ),
          ),
        )
      }
    }

    def asyncMicro: Observable[A]    = evalOn(MicrotaskExecutor)
    def asyncMacro: Observable[A]    = evalOn(MacrotaskExecutor)
    @inline def async: Observable[A] = asyncMacro

    @inline def delay(duration: FiniteDuration): Observable[A] = delayMillis(duration.toMillis.toInt)

    def delayMillis(duration: Int): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var lastTimeout: js.UndefOr[timers.SetTimeoutHandle] = js.undefined
        var isCancel                                         = false
        var openRuns                                         = 0

        // TODO: we only actually cancel the last timeout. The check isCancel
        // makes sure that unsafeCancelled subscription is really respected.
        Cancelable.composite(
          Cancelable.withIsEmpty(openRuns == 0) { () =>
            isCancel = true
            lastTimeout.foreach(timers.clearTimeout)
          },
          source.unsafeSubscribe(
            Observer.create[A](
              { value =>
                openRuns += 1
                lastTimeout = timers.setTimeout(duration.toDouble) {
                  openRuns -= 1
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

    @deprecated("Manage subscriptions directly with subscribe, to, via, etc.", "0.4.3")
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

    @inline def publish: Connectable[Observable[A]]                 = multicast(Subject.publish[A]())
    @deprecated("Use replayLatest instead", "0.3.4")
    @inline def replay: Connectable[Observable.MaybeValue[A]]       = replayLatest
    @inline def replayLatest: Connectable[Observable.MaybeValue[A]] = multicastMaybeValue(Subject.replayLatest[A]())
    @inline def replayAll: Connectable[Observable[A]]               = multicast(Subject.replayAll[A]())
    @inline def behavior(seed: A): Connectable[Observable.Value[A]] = multicastValue(Subject.behavior(seed))

    @inline def publishShare: Observable[A]                 = publish.refCount
    @inline def replayLatestShare: Observable.MaybeValue[A] = replayLatest.refCount
    @inline def replayAllShare: Observable[A]               = replayAll.refCount
    @inline def behaviorShare(seed: A): Observable.Value[A] = behavior(seed).refCount

    @inline def publishSelector[B](f: Observable[A] => Observable[B]): Observable[B]                  = transformSource(s => f(s.publish.refCount))
    @deprecated("Use replayLatestSelector instead", "0.3.4")
    @inline def replaySelector[B](f: Observable.MaybeValue[A] => Observable[B]): Observable[B]        = replayLatestSelector(f)
    @inline def replayLatestSelector[B](f: Observable.MaybeValue[A] => Observable[B]): Observable[B]  =
      transformSource(s => f(s.replayLatest.refCount))
    @inline def replayAllSelector[B](f: Observable[A] => Observable[B]): Observable[B]                =
      transformSource(s => f(s.replayAll.refCount))
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

    def fold[B](seed: B)(f: (B, A) => B): Observable[B]         = scan(seed)(f).last
    def foldF[F[_]: Async, B](seed: B)(f: (B, A) => B): F[B]    = scan(seed)(f).lastF[F]
    def foldIO[B](seed: B)(f: (B, A) => B): IO[B]               = scan(seed)(f).lastIO
    def unsafeFoldFuture[B](seed: B)(f: (B, A) => B): Future[B] = scan(seed)(f).unsafeLastFuture()

    @deprecated("Use prependEffect instead", "0.3.0")
    @inline def prependSync[F[_]: RunSyncEffect](value: F[A]): Observable[A] = prependEffect(value)
    @deprecated("Use prependEffect instead", "0.3.0")
    @inline def prependAsync[F[_]: RunEffect](value: F[A]): Observable[A]    = prependEffect(value)

    @inline def prependEffect[F[_]: RunEffect](value: F[A]): Observable[A] = concatEffect[F, A](value, source)
    @inline def prependFuture(value: => Future[A]): Observable[A]          = concatFuture[A](value, source)

    def prepend(value: A): Observable[A]        = prependEval(value)
    def prependEval(value: => A): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        sink.unsafeOnNext(value)
        source.unsafeSubscribe(sink)
      }
    }

    @inline def appendEffect[F[_]: RunEffect](value: F[A]): Observable[A] = concat(Observable.fromEffect(value))
    @inline def appendFuture(value: => Future[A]): Observable[A]          = concat(Observable.fromFuture(value))

    @inline def append(value: A): Observable[A]        = concat(Observable(value))
    @inline def appendEval(value: => A): Observable[A] = concat(Observable.eval(value))

    def startWith(values: Iterable[A]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        values.foreach(sink.unsafeOnNext)
        source.unsafeSubscribe(sink)
      }
    }

    def endWith(values: Iterable[A]): Observable[A] = concat(Observable.fromIterable(values))

    def syncLatestF[F[_]: Sync]: F[Option[A]] = Sync[F].defer {
      var lastValue = Option.empty[F[A]]

      val cancelable = source.unsafeSubscribe(
        Observer.create[A](value => lastValue = Some(Sync[F].pure(value)), error => lastValue = Some(Sync[F].raiseError(error))),
      )
      cancelable.unsafeCancel()

      lastValue.sequence
    }

    @inline def syncLatestIO: IO[Option[A]] = syncLatestF[IO]

    @inline def syncLatestSyncIO: SyncIO[Option[A]] = syncLatestF[SyncIO]

    @inline def syncLatest: Observable[A] = Observable.fromEffect(syncLatestSyncIO).flattenOption

    def syncAllF[F[_]: Sync]: F[Seq[A]] = Sync[F].defer {
      val values = collection.mutable.ArrayBuffer[F[A]]()

      val cancelable = source.unsafeSubscribe(
        Observer.create[A](value => values.addOne(Sync[F].pure(value)), error => values.addOne(Sync[F].raiseError(error))),
      )
      cancelable.unsafeCancel()

      values.toSeq.sequence
    }

    @inline def syncAllIO: IO[Seq[A]] = syncAllF[IO]

    @inline def syncAllSyncIO: SyncIO[Seq[A]] = syncAllF[SyncIO]

    @inline def syncAll: Observable[A] = Observable.fromEffect(syncAllSyncIO).flattenIterable

    def headF[F[_]: Async]: F[A] = Async[F].async[A] { callback =>
      Async[F].delay {
        val cancelable = Cancelable.variable()
        var isDone     = false

        def dispatch(value: Either[Throwable, A]) = if (!isDone) {
          isDone = true
          cancelable.unsafeCancel()
          callback(value)
        }

        cancelable.unsafeAdd(() =>
          source.unsafeSubscribe(Observer.create[A](value => dispatch(Right(value)), error => dispatch(Left(error)))),
        )

        cancelable.unsafeFreeze()

        Some(Async[F].delay(cancelable.unsafeCancel()))
      }
    }

    @inline def headIO: IO[A] = headF[IO]

    @inline def unsafeHeadFuture(): Future[A] = headIO.unsafeToFuture()(cats.effect.unsafe.IORuntime.global)

    @inline def head: Observable[A] = take(1)

    def lastF[F[_]: Async]: F[A] = Async[F].async[A] { callback =>
      Async[F].delay {
        var lastValue: Either[Throwable, A] = null
        val cancelable                      = Cancelable.variable()

        var innerCancelCheck      = false
        var innerCheckIsScheduled = false

        def dispatch(value: Either[Throwable, A]) = {
          lastValue = value
          if (!innerCheckIsScheduled) {
            innerCheckIsScheduled = true
            innerCancelCheck = false
            MicrotaskExecutor.execute { () =>
              innerCheckIsScheduled = false
              if (!innerCancelCheck && cancelable.isEmpty()) callback(lastValue)
            }
          }
        }

        cancelable.unsafeAdd(() =>
          source.unsafeSubscribe(Observer.create[A](value => dispatch(Right(value)), error => dispatch(Left(error)))),
        )

        cancelable.unsafeFreeze()

        if (cancelable.isEmpty() && lastValue != null) {
          innerCancelCheck = true
          callback(lastValue)
        }

        Some(Async[F].delay(cancelable.unsafeCancel()))
      }
    }

    @inline def lastIO: IO[A] = lastF[IO]

    @inline def unsafeLastFuture(): Future[A] = lastIO.unsafeToFuture()(cats.effect.unsafe.IORuntime.global)

    @inline def last: Observable[A] = Observable.fromEffect(lastIO)

    @inline def tail: Observable[A] = drop(1)

    def take(num: Int): Observable[A] = {
      if (num <= 0) Observable.empty
      else
        new Observable[A] {
          def unsafeSubscribe(sink: Observer[A]): Cancelable = {
            var counter      = 0
            val subscription = Cancelable.variable()
            subscription.unsafeAdd(() =>
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
            subscription.unsafeFreeze()

            subscription
          }
        }
    }

    def takeWhile(predicate: A => Boolean): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var finishedTake = false
        val subscription = Cancelable.variable()
        subscription.unsafeAdd(() =>
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
        subscription.unsafeFreeze()

        subscription
      }
    }

    def takeUntil(until: Observable[Unit]): Observable[A] = new Observable[A] {
      def unsafeSubscribe(sink: Observer[A]): Cancelable = {
        var finishedTake = false
        val subscription = Cancelable.builder()

        subscription.unsafeAdd(() =>
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

        if (!finishedTake) subscription.unsafeAdd(() => source.unsafeSubscribe(sink.contrafilter(_ => !finishedTake)))

        subscription.unsafeFreeze()

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
        untilCancelable.unsafeAdd(() =>
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

        untilCancelable.unsafeFreeze()

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
    @deprecated("Use unsafeForeach(f) or foreach_(f).subscribeF[F] instead", "0.3.0")
    @inline def foreach(f: A => Unit): Cancelable        = unsafeForeach(f)
  }

  @inline implicit class ThrowableOperations(private val source: Observable[Throwable]) extends AnyVal {
    @inline def mergeFailed: Observable[Throwable] = source.recoverMap(identity)
  }

  @inline implicit class BooleanOperations(private val source: Observable[Boolean]) extends AnyVal {
    @inline def asIf[A](ifTrue: => A, ifFalse: A): Observable[A] = source.map {
      case true  => ifTrue
      case false => ifFalse
    }

    @inline def asIf[A: Monoid](ifTrue: => A): Observable[A] = asIf(ifTrue, Monoid[A].empty)
  }

  @inline implicit class IterableOperations[A](private val source: Observable[Iterable[A]]) extends AnyVal {
    @inline def flattenIterable: Observable[A] = source.mapIterable(identity)
  }

  @inline implicit class EitherOperations[A](private val source: Observable[Either[Throwable, A]]) extends AnyVal {
    @inline def flattenEither: Observable[A] = source.mapEither(identity)
  }

  @inline implicit class OptionOperations[A](private val source: Observable[Option[A]]) extends AnyVal {
    @inline def flattenOption: Observable[A] = source.mapFilter(identity)
  }

  @inline implicit class ObservableLikeOperations[F[_]: ObservableLike, A](val source: Observable[F[A]]) {
    @inline def flatten: Observable[A]       = source.flatMap(o => ObservableLike[F].toObservable(o))
    @inline def flattenConcat: Observable[A] = source.concatMap(o => ObservableLike[F].toObservable(o))
    @inline def flattenMerge: Observable[A]  = source.mergeMap(o => ObservableLike[F].toObservable(o))
    @inline def flattenSwitch: Observable[A] = source.switchMap(o => ObservableLike[F].toObservable(o))
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
