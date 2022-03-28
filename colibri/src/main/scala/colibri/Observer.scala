package colibri

import cats.effect.{Sync, SyncIO, IO}
import cats.{MonoidK, Contravariant}
import colibri.helpers._

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

  @deprecated("Use createUnrecovered instead", "")
  @inline def unsafeCreate[A](
      consume: A => Unit,
      failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.unsafeOnNext,
  ): Observer[A] = createUnrecovered(consume, failure)
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

  @inline def combine[A](sinks: Observer[A]*): Observer[A] = combineSeq(sinks)

  def combineSeq[A](sinks: Seq[Observer[A]]): Observer[A] = new Observer[A] {
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

  implicit object monoidK extends MonoidK[Observer] {
    @inline def empty[T]                                    = Observer.empty
    @inline def combineK[T](a: Observer[T], b: Observer[T]) = Observer.combine(a, b)
  }

  implicit object contravariant extends Contravariant[Observer] {
    @inline def contramap[A, B](fa: Observer[A])(f: B => A): Observer[B] = fa.contramap(f)
  }

  @inline implicit class Operations[A](val sink: Observer[A]) extends AnyVal {
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

    def contraflattenIterable[B]: Observer[Iterable[A]] = contramapIterable(identity)

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

    def doOnNext(f: A => Unit): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = f(value)
      def unsafeOnError(error: Throwable): Unit = sink.unsafeOnError(error)
    }

    def doOnError(f: Throwable => Unit): Observer[A] = new Observer[A] {
      def unsafeOnNext(value: A): Unit          = sink.unsafeOnNext(value)
      def unsafeOnError(error: Throwable): Unit = f(error)
    }

    def redirect[B](transform: Observable[B] => Observable[A]): Connectable[Observer[B]] = {
      val handler = Subject.publish[B]()
      val source  = transform(handler)
      Connectable(handler, () => source.unsafeSubscribe(sink))
    }

    @deprecated("Use unsafeOnNext instead", "")
    def onNext(value: A): Unit          = sink.unsafeOnNext(value)
    @deprecated("Use unsafeOnError instead", "")
    def onError(error: Throwable): Unit = sink.unsafeOnError(error)

    def onNextF[F[_]: Sync](value: A): F[Unit] = Sync[F].delay(sink.unsafeOnNext(value))
    def onNextIO(value: A): IO[Unit]           = onNextF[IO](value)
    def onNextSyncIO(value: A): SyncIO[Unit]   = onNextF[SyncIO](value)

    def onErrorF[F[_]: Sync](error: Throwable): F[Unit] = Sync[F].delay(sink.unsafeOnError(error))
    def onErrorIO(error: Throwable): IO[Unit]           = onErrorF[IO](error)
    def onErrorSyncIO(error: Throwable): SyncIO[Unit]   = onErrorF[SyncIO](error)
  }

  private def recovered(action: => Unit, unsafeOnError: Throwable => Unit): Unit = try action
  catch { case NonFatal(t) => unsafeOnError(t) }
}
