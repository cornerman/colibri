package colibri

import cats.{MonoidK, Contravariant}
import colibri.helpers._

import scala.util.control.NonFatal

trait Observer[-A] {
  def onNext(value: A): Unit
  def onError(error: Throwable): Unit
}
object Observer    {

  object Empty extends Observer[Any] {
    @inline def onNext(value: Any): Unit        = ()
    @inline def onError(error: Throwable): Unit = UnhandledErrorReporter.errorSubject.onNext(error)
  }

  @inline def empty = Empty

  def lift[G[_]: Sink, A](sink: G[A]): Observer[A] = sink match {
    case sink: Observer[A @unchecked] => sink
    case _                            =>
      new Observer[A] {
        def onNext(value: A): Unit          = Sink[G].onNext(sink)(value)
        def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
      }
  }

  @inline def unsafeCreate[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] =
    new Observer[A] {
      def onNext(value: A): Unit          = consume(value)
      def onError(error: Throwable): Unit = failure(error)
    }

  def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] =
    new Observer[A] {
      def onNext(value: A): Unit          = recovered(consume(value), onError)
      def onError(error: Throwable): Unit = failure(error)
    }

  @inline def combine[A](sinks: Observer[A]*): Observer[A] = combineSeq(sinks)

  def combineSeq[A](sinks: Seq[Observer[A]]): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit          = sinks.foreach(_.onNext(value))
    def onError(error: Throwable): Unit = sinks.foreach(_.onError(error))
  }

  implicit object liftSink extends LiftSink[Observer] {
    @inline def lift[G[_]: Sink, A](sink: G[A]): Observer[A] = Observer.lift(sink)
  }

  implicit object sink extends Sink[Observer] {
    @inline def onNext[A](sink: Observer[A])(value: A): Unit          = sink.onNext(value)
    @inline def onError[A](sink: Observer[A])(error: Throwable): Unit = sink.onError(error)
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
      def onNext(value: B): Unit          = recovered(sink.onNext(f(value)), onError)
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def contramapEither[B](f: B => Either[Throwable, A]): Observer[B] = new Observer[B] {
      def onNext(value: B): Unit          = recovered(
        f(value) match {
          case Right(value) => sink.onNext(value)
          case Left(error)  => sink.onError(error)
        },
        onError,
      )
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def contramapFilter[B](f: B => Option[A]): Observer[B] = new Observer[B] {
      def onNext(value: B): Unit          = recovered(f(value).foreach(sink.onNext), onError)
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def contracollect[B](f: PartialFunction[B, A]): Observer[B] = new Observer[B] {
      def onNext(value: B): Unit          = recovered({ f.runWith(sink.onNext)(value); () }, onError)
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def contrafilter(f: A => Boolean): Observer[A] = new Observer[A] {
      def onNext(value: A): Unit          = recovered(if (f(value)) sink.onNext(value), onError)
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def contramapIterable[B](f: B => Iterable[A]): Observer[B] = new Observer[B] {
      def onNext(value: B): Unit          = recovered(f(value).foreach(sink.onNext(_)), onError)
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def contraflattenIterable[B]: Observer[Iterable[A]] = contramapIterable(identity)

    //TODO return effect
    def contrascan[B](seed: A)(f: (A, B) => A): Observer[B] = new Observer[B] {
      private var state                   = seed
      def onNext(value: B): Unit          = recovered(
        {
          val result = f(state, value)
          state = result
          sink.onNext(result)
        },
        onError,
      )
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def doOnNext(f: A => Unit): Observer[A] = new Observer[A] {
      def onNext(value: A): Unit          = f(value)
      def onError(error: Throwable): Unit = sink.onError(error)
    }

    def doOnError(f: Throwable => Unit): Observer[A] = new Observer[A] {
      def onNext(value: A): Unit          = sink.onNext(value)
      def onError(error: Throwable): Unit = f(error)
    }

    def redirect[B](transform: Observable[B] => Observable[A]): Connectable[Observer[B]] = {
      val handler = Subject.publish[B]
      val source  = transform(handler)
      Connectable(handler, () => source.subscribe(sink))
    }
  }

  private def recovered(action: => Unit, onError: Throwable => Unit): Unit = try action
  catch { case NonFatal(t) => onError(t) }
}
