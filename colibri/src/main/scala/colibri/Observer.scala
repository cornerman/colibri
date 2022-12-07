package colibri

import cats.effect.{Sync, SyncIO, IO}
import cats.{MonoidK, ContravariantMonoidal}
import colibri.helpers._
import scala.concurrent.Promise

import scala.util.control.NonFatal

trait Observer[-A] {
  def unsafeOnNext(value: A): Unit
  def unsafeOnError(error: Throwable): Unit
}
object Observer    {

  object Empty extends Observer[Any] {
    @inline def unsafeOnNext(value: Any): Unit        = ()
    @inline def unsafeOnError(error: Throwable): Unit = UnhandledErrorReporter.errorSubject.unsafeOnNext(error)
  }

  @inline def empty = Empty

  def lift[G[_]: Sink, A](sink: G[A]): Observer[A] = sink match {
    case sink: Observer[A @unchecked] => sink
    case _                            =>
      new Observer[A] {
        def unsafeOnNext(value: A): Unit          = Sink[G].unsafeOnNext(sink)(value)
        def unsafeOnError(error: Throwable): Unit = Sink[G].unsafeOnError(sink)(error)
      }
  }

  @inline def createUnrecovered[A](
      consume: A => Unit,
      failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.unsafeOnNext,
  ): Observer[A] =
    new Observer[A] {
      def unsafeOnNext(value: A): Unit          = consume(value)
      def unsafeOnError(error: Throwable): Unit = failure(error)
    }

  def foreach[A](consume: A => Unit): Observer[A] = Observer.create(consume)

  def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.unsafeOnNext): Observer[A] =
    new Observer[A] {
      def unsafeOnNext(value: A): Unit          = recovered(consume(value), unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = failure(error)
    }

  def createFromEither[A](f: Either[Throwable, A] => Unit): Observer[A] =
    new Observer[A] {
      def unsafeOnNext(value: A): Unit          = recovered(f(Right(value)), unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = f(Left(error))
    }

  def createFromPromise[A](promise: Promise[A]): Observer[A] =
    new Observer[A] {
      def unsafeOnNext(value: A): Unit          = { promise.trySuccess(value); () }
      def unsafeOnError(error: Throwable): Unit = { promise.tryFailure(error); () }
    }

  def product[A, B](fa: Observer[A], fb: Observer[B]): Observer[(A, B)] = new Observer[(A, B)] {
    def unsafeOnNext(value: (A, B)): Unit     = {
      fa.unsafeOnNext(value._1)
      fb.unsafeOnNext(value._2)
    }
    def unsafeOnError(error: Throwable): Unit = {
      fa.unsafeOnError(error)
      fb.unsafeOnError(error)
    }
  }

  def debugLog[A]: Observer[A]                 = debugLog("")
  def debugLog[A](prefix: String): Observer[A] =
    new Observer[A] {
      private var index = 0

      def unsafeOnNext(value: A): Unit          = {
        println(s"$index: $prefix - $value")
        index += 1
      }
      def unsafeOnError(error: Throwable): Unit = {
        println(s"ERROR: $prefix - $error")
      }
    }

  @inline def combine[A](sinks: Observer[A]*): Observer[A] = combineIterable(sinks)

  def combineIterable[A](sinks: Iterable[Observer[A]]): Observer[A] = new Observer[A] {
    def unsafeOnNext(value: A): Unit          = sinks.foreach(_.unsafeOnNext(value))
    def unsafeOnError(error: Throwable): Unit = sinks.foreach(_.unsafeOnError(error))
  }

  implicit object liftSink extends LiftSink[Observer] {
    @inline def lift[G[_]: Sink, A](sink: G[A]): Observer[A] = Observer.lift(sink)
  }

  implicit object sink extends Sink[Observer] {
    @inline def unsafeOnNext[A](sink: Observer[A])(value: A): Unit          = sink.unsafeOnNext(value)
    @inline def unsafeOnError[A](sink: Observer[A])(error: Throwable): Unit = sink.unsafeOnError(error)
  }

  implicit object catsInstances extends ContravariantMonoidal[Observer] with MonoidK[Observer] {
    @inline override def empty[T]                                    = Observer.empty
    @inline override def combineK[T](a: Observer[T], b: Observer[T]) = Observer.combine(a, b)

    @inline override def contramap[A, B](fa: Observer[A])(f: B => A): Observer[B]          = fa.contramap(f)
    @inline override def unit: Observer[Unit]                                              = Observer.empty
    @inline override def product[A, B](fa: Observer[A], fb: Observer[B]): Observer[(A, B)] = Observer.product(fa, fb)
  }

  @inline implicit class Operations[A](private val sink: Observer[A]) extends AnyVal {
    def liftSource[G[_]: LiftSink]: G[A] = LiftSink[G].lift(sink)

    def contramap[B](f: B => A): Observer[B] = new Observer[B] {
      def unsafeOnNext(value: B): Unit          = recovered(sink.unsafeOnNext(f(value)), unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def contramapEither[B](f: B => Either[Throwable, A]): Observer[B] = new Observer[B] {
      def unsafeOnNext(value: B): Unit          = recovered(
        f(value) match {
          case Right(value) => sink.unsafeOnNext(value)
          case Left(error)  => sink.unsafeOnError(error)
        },
        unsafeOnError,
      )
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def contramapFilter[B](f: B => Option[A]): Observer[B] = new Observer[B] {
      def unsafeOnNext(value: B): Unit          = recovered(f(value).foreach(sink.unsafeOnNext), unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def contracollect[B](f: PartialFunction[B, A]): Observer[B] = new Observer[B] {
      def unsafeOnNext(value: B): Unit          = recovered({ f.runWith(sink.unsafeOnNext)(value); () }, unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def contrafilter(f: A => Boolean): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = recovered(if (f(value)) sink.unsafeOnNext(value), unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def contramapIterable[B](f: B => Iterable[A]): Observer[B] = new Observer[B] {
      def unsafeOnNext(value: B): Unit          = recovered(f(value).foreach(sink.unsafeOnNext(_)), unsafeOnError)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def contraflattenIterable: Observer[Iterable[A]]        = contramapIterable(identity)
    def contraflattenEither: Observer[Either[Throwable, A]] = contramapEither(identity)
    def contraflattenOption: Observer[Option[A]]            = contramapFilter(identity)

    // TODO return effect
    def contrascan[B](seed: A)(f: (A, B) => A): Observer[B] = new Observer[B] {
      private var state                         = seed
      def unsafeOnNext(value: B): Unit          = recovered(
        {
          val result = f(state, value)
          state = result
          sink.unsafeOnNext(result)
        },
        unsafeOnError,
      )
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def as(value: A): Observer[Any] = sink.contramap(_ => value)

    def tap(f: A => Unit): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = {
        f(value)
        sink.unsafeOnNext(value)
      }
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def tapFailed(f: Throwable => Unit): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = sink.unsafeOnNext(value)
      def unsafeOnError(error: Throwable): Unit = {
        f(error)
        sink.unsafeOnError(error)
      }
    }

    def combine(obs: Observer[A]): Observer[A] = Observer.combine(sink, obs)

    def doOnNext(f: A => Unit): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = f(value)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def doOnError(f: Throwable => Unit): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = sink.unsafeOnNext(value)
      def unsafeOnError(error: Throwable): Unit = f(error)
    }

    def dropOnNext: Observer[A]  = doOnNext(_ => ())
    def dropOnError: Observer[A] = doOnError(_ => ())

    def redirect[B](transform: Observable[B] => Observable[A]): Connectable[Observer[B]] = {
      val handler = Subject.publish[B]()
      val source  = transform(handler)
      Connectable(handler, () => source.unsafeSubscribe(sink))
    }

    def redirectWithLatest[B](transform: Observable[B] => Observable[A]): Connectable[Observer[B]] = {
      val handler = Subject.replayLatest[B]()
      val source  = transform(handler)
      Connectable(handler, () => source.unsafeSubscribe(sink))
    }

    def onNextF[F[_]: Sync](value: A): F[Unit] = Sync[F].delay(sink.unsafeOnNext(value))
    def onNextIO(value: A): IO[Unit]           = onNextF[IO](value)
    def onNextSyncIO(value: A): SyncIO[Unit]   = onNextF[SyncIO](value)

    def onErrorF[F[_]: Sync](error: Throwable): F[Unit] = Sync[F].delay(sink.unsafeOnError(error))
    def onErrorIO(error: Throwable): IO[Unit]           = onErrorF[IO](error)
    def onErrorSyncIO(error: Throwable): SyncIO[Unit]   = onErrorF[SyncIO](error)
  }

  @inline implicit class UnitOperations(private val sink: Observer[Unit]) extends AnyVal {
    @inline def void: Observer[Any] = sink.as(())
  }

  @inline implicit class ThrowableOperations(private val sink: Observer[Throwable]) extends AnyVal {
    def failed: Observer[Any] = Observer.createUnrecovered(_ => (), sink.unsafeOnNext(_))
  }

  private def recovered(action: => Unit, unsafeOnError: Throwable => Unit): Unit = try action
  catch { case NonFatal(t) => unsafeOnError(t) }
}
