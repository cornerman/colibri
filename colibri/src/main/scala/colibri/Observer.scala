package colibri

import cats.syntax.all._
import cats.implicits._
import cats.{MonoidK, Contravariant}
import cats.effect.Sync
import colibri.helpers._

import scala.util.control.NonFatal

trait Observer[-A] {
  def onNext[F[_] : Sync](value: A): F[Unit]
  def onError[F[_] : Sync](error: Throwable): F[Unit]
}
object Observer {

  object Empty extends Observer[Any] {
    @inline def onNext[F[_] : Sync](value: Any): F[Unit] = ().pure[F]
    @inline def onError[F[_] : Sync](error: Throwable): F[Unit] = UnhandledErrorReporter.errorSubject.onNext(error)
  }

  @inline def empty = Empty

  class Connectable[-T](
    val sink: Observer[T],
    val connect: () => Cancelable
  )
  @inline def connectable[F[_] : Sync, T](sink: Observer[T], connect: () => F[Cancelable]): Connectable[T] = {
    val cancelable = Cancelable.refCount(connect)
    new Connectable(sink, () => cancelable.ref())
  }

  def lift[G[_] : Sink, A](sink: G[A]): Observer[A] =  sink match {
    case sink: Observer[A@unchecked] => sink
    case _ => new Observer[A] {
      def onNext[F[_] : Sync](value: A): F[Unit] = Sink[G].onNext(sink)(value)
      def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
    }
  }

  @inline def unsafeCreate[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = consume(value).pure[F]
    def onError[F[_] : Sync](error: Throwable): F[Unit] = failure(error).pure[F]
  }

  def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = recoveredF(consume(value).pure[F], onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = failure(error).pure[F]
  }

  @inline def combine[G[_] : Sink, A](sinks: G[A]*): Observer[A] = combineSeq(sinks)

  def combineSeq[G[_] : Sink, A](sinks: Seq[G[A]]): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = sinks.toList.traverse_(Sink[G].onNext(_)(value))
    def onError[F[_] : Sync](error: Throwable): F[Unit] = sinks.toList.traverse_(Sink[G].onError(_)(error))
  }

  def combineVaried[GA[_] : Sink, GB[_] : Sink, A](sinkA: GA[A], sinkB: GB[A]): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = Sink[GA].onNext(sinkA)(value) *> Sink[GB].onNext(sinkB)(value)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[GA].onError(sinkA)(error) *> Sink[GB].onError(sinkB)(error)
  }

  def contramap[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => A): Observer[B] = new Observer[B] {
    def onNext[F[_] : Sync](value: B): F[Unit] = recoveredF(Sink[G].onNext(sink)(f(value)), onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def contramapEither[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Either[Throwable, A]): Observer[B] = new Observer[B] {
    def onNext[F[_] : Sync](value: B): F[Unit] = recoveredF(f(value) match {
      case Right(value) => Sink[G].onNext(sink)(value)
      case Left(error) => Sink[G].onError(sink)(error)
    }, onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def contramapFilter[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Option[A]): Observer[B] = new Observer[B] {
    def onNext[F[_] : Sync](value: B): F[Unit] = recoveredF(f(value).traverse_(Sink[G].onNext(sink)), onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def contracollect[G[_] : Sink, A, B](sink: G[_ >: A])(f: PartialFunction[B, A]): Observer[B] = new Observer[B] {
    def onNext[F[_] : Sync](value: B): F[Unit] = recoveredF(f.lift(value).traverse_(Sink[G].onNext(sink)), onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def contrafilter[G[_] : Sink, A](sink: G[_ >: A])(f: A => Boolean): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = recoveredF(Sink[G].onNext(sink)(value).whenA(f(value)), onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def contramapIterable[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Iterable[A]): Observer[B] = new Observer[B] {
    def onNext[F[_] : Sync](value: B): F[Unit] = recoveredF(f(value).toList.traverse_(Sink[G].onNext(sink)), onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def contraflattenIterable[G[_] : Sink, A, B](sink: G[_ >: A]): Observer[Iterable[A]] = contramapIterable(sink)(identity)

  //TODO return effect
  def contrascan[G[_] : Sink, A, B](sink: G[_ >: A])(seed: A)(f: (A, B) => A): Observer[B] = new Observer[B] {
    private var state = seed
    def onNext[F[_] : Sync](value: B): F[Unit] = recoveredF({
      val result = f(state, value)
      state = result
      Sink[G].onNext(sink)(result)
    }, onError)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def doOnNext[G[_] : Sink, A](sink: G[_ >: A])(f: A => Unit): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = f(value).pure[F]
    def onError[F[_] : Sync](error: Throwable): F[Unit] = Sink[G].onError(sink)(error)
  }

  def doOnError[G[_] : Sink, A](sink: G[_ >: A])(f: Throwable => Unit): Observer[A] = new Observer[A] {
    def onNext[F[_] : Sync](value: A): F[Unit] = Sink[G].onNext(sink)(value)
    def onError[F[_] : Sync](error: Throwable): F[Unit] = f(error).pure[F]
  }

  def redirect[F[_] : Sync, G[_] : Sink, H[_] : Source, A, B](sink: G[_ >: A])(transform: Observable[B] => H[A]): Connectable[B] = {
    val handler = Subject.publish[B]
    val source = transform(handler)
    connectable(handler, () => Source[H].subscribe(source)(sink))
  }

  implicit object liftSink extends LiftSink[Observer] {
    @inline def lift[G[_] : Sink, A](sink: G[A]): Observer[A] = Observer.lift(sink)
  }

  implicit object sink extends Sink[Observer] {
    @inline def onNext[F[_] : Sync, A](sink: Observer[A])(value: A): F[Unit] = sink.onNext(value)
    @inline def onError[F[_] : Sync, A](sink: Observer[A])(error: Throwable): F[Unit] = sink.onError(error)
  }

  implicit object monoidK extends MonoidK[Observer] {
    @inline def empty[T] = Observer.empty
    @inline def combineK[T](a: Observer[T], b: Observer[T]) = Observer.combineVaried(a, b)
  }

  implicit object contravariant extends Contravariant[Observer] {
    @inline def contramap[A, B](fa: Observer[A])(f: B => A): Observer[B] = Observer.contramap(fa)(f)
  }

  @inline implicit class Operations[A](val observer: Observer[A]) extends AnyVal {
    @inline def liftSink[G[_] : LiftSink]: G[A] = LiftSink[G].lift(observer)
    @inline def contramap[B](f: B => A): Observer[B] = Observer.contramap(observer)(f)
    @inline def contramapEither[B](f: B => Either[Throwable,A]): Observer[B] = Observer.contramapEither(observer)(f)
    @inline def contramapFilter[B](f: B => Option[A]): Observer[B] = Observer.contramapFilter(observer)(f)
    @inline def contracollect[B](f: PartialFunction[B, A]): Observer[B] = Observer.contracollect(observer)(f)
    @inline def contrafilter(f: A => Boolean): Observer[A] = Observer.contrafilter(observer)(f)
    @inline def contramapIterable[B](f: B => Iterable[A]): Observer[B] = Observer.contramapIterable(observer)(f)
    @inline def contraflattenIterable: Observer[Iterable[A]] = Observer.contraflattenIterable(observer)
    @inline def contrascan[B](seed: A)(f: (A, B) => A): Observer[B] = Observer.contrascan(observer)(seed)(f)
    @inline def doOnError(f: Throwable => Unit): Observer[A] = Observer.doOnError(observer)(f)
    @inline def combine[G[_] : Sink](sink: G[A]): Observer[A] = Observer.combine(observer, Observer.lift(sink))
    @inline def redirect[F[_] : Sync, H[_] : Source, B](f: Observable[B] => H[A]): Observer.Connectable[B] = Observer.redirect(observer)(f)
  }

  private def recovered(action: => Unit, onError: Throwable => Unit): Unit = try action catch { case NonFatal(t) => onError(t) }
  private def recoveredF[F[_] : Sync](action: => F[Unit], onError: Throwable => F[Unit]): F[Unit] = try action catch { case NonFatal(t) => onError(t) }
}
