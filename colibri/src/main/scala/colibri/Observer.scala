package colibri

import cats.{MonoidK, Contravariant}
import colibri.helpers._

import scala.util.control.NonFatal

trait Observer[-A] {
  def onNext(value: A): Unit
  def onError(error: Throwable): Unit
}
object Observer {

  object Empty extends Observer[Any] {
    @inline def onNext(value: Any): Unit = ()
    @inline def onError(error: Throwable): Unit = ()
  }

  @inline def empty = Empty

  class Connectable[-T](
    val sink: Observer[T],
    val connect: () => Cancelable
  )
  @inline def connectable[T](sink: Observer[T], connect: () => Cancelable): Connectable[T] = {
    val cancelable = Cancelable.refCount(connect)
    new Connectable(sink, () => cancelable.ref())
  }

  def lift[G[_] : Sink, A](sink: G[A]): Observer[A] =  sink match {
    case sink: Observer[A@unchecked] => sink
    case _ => new Observer[A] {
      def onNext(value: A): Unit = Sink[G].onNext(sink)(value)
      def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
    }
  }

  @inline def unsafeCreate[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = consume(value)
    def onError(error: Throwable): Unit = failure(error)
  }

  def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = recovered(consume(value), onError)
    def onError(error: Throwable): Unit = failure(error)
  }

  @inline def combine[G[_] : Sink, A](sinks: G[A]*): Observer[A] = combineSeq(sinks)

  def combineSeq[G[_] : Sink, A](sinks: Seq[G[A]]): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = sinks.foreach(Sink[G].onNext(_)(value))
    def onError(error: Throwable): Unit = sinks.foreach(Sink[G].onError(_)(error))
  }

  def combineVaried[GA[_] : Sink, GB[_] : Sink, A](sinkA: GA[A], sinkB: GB[A]): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = {
      Sink[GA].onNext(sinkA)(value)
      Sink[GB].onNext(sinkB)(value)
    }
    def onError(error: Throwable): Unit = {
      Sink[GA].onError(sinkA)(error)
      Sink[GB].onError(sinkB)(error)
    }
  }

  def contramap[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => A): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered(Sink[G].onNext(sink)(f(value)), onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contramapEither[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Either[Throwable, A]): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered(f(value) match {
      case Right(value) => Sink[G].onNext(sink)(value)
      case Left(error) => Sink[G].onError(sink)(error)
    }, onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contramapFilter[G[_] : Sink, A, B](sink: G[_ >: A])(f: B => Option[A]): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered(f(value).foreach(Sink[G].onNext(sink)), onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contracollect[G[_] : Sink, A, B](sink: G[_ >: A])(f: PartialFunction[B, A]): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered({ f.runWith(Sink[G].onNext(sink))(value); () }, onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def contrafilter[G[_] : Sink, A](sink: G[_ >: A])(f: A => Boolean): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = recovered(if (f(value)) Sink[G].onNext(sink)(value), onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  //TODO return effect
  def contrascan[G[_] : Sink, A, B](sink: G[_ >: A])(seed: A)(f: (A, B) => A): Observer[B] = new Observer[B] {
    private var state = seed
    def onNext(value: B): Unit = recovered({
      val result = f(state, value)
      state = result
      Sink[G].onNext(sink)(result)
    }, onError)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def doOnNext[G[_] : Sink, A](sink: G[_ >: A])(f: A => Unit): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = f(value)
    def onError(error: Throwable): Unit = Sink[G].onError(sink)(error)
  }

  def doOnError[G[_] : Sink, A](sink: G[_ >: A])(f: Throwable => Unit): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = Sink[G].onNext(sink)(value)
    def onError(error: Throwable): Unit = f(error)
  }

  def redirect[G[_] : Sink, S[_] : Source, A, B](sink: G[_ >: A])(transform: Observable[B] => S[A]): Connectable[B] = {
    val handler = Subject.publish[B]
    val source = transform(handler)
    connectable(handler, () => Source[S].subscribe(source)(sink))
  }

  implicit object liftSink extends LiftSink[Observer] {
    @inline def lift[G[_] : Sink, A](sink: G[A]): Observer[A] = Observer.lift(sink)
  }

  implicit object sink extends Sink[Observer] {
    @inline def onNext[A](sink: Observer[A])(value: A): Unit = sink.onNext(value)
    @inline def onError[A](sink: Observer[A])(error: Throwable): Unit = sink.onError(error)
  }

  implicit object monoidK extends MonoidK[Observer] {
    @inline def empty[T] = Observer.empty
    @inline def combineK[T](a: Observer[T], b: Observer[T]) = Observer.combineVaried(a, b)
  }

  implicit object contravariant extends Contravariant[Observer] {
    @inline def contramap[A, B](fa: Observer[A])(f: B => A): Observer[B] = Observer.contramap(fa)(f)
  }

  @inline implicit class Operations[A](val sink: Observer[A]) extends AnyVal {
    @inline def liftSink[G[_] : LiftSink]: G[A] = LiftSink[G].lift(sink)
    @inline def contramap[B](f: B => A): Observer[B] = Observer.contramap(sink)(f)
    @inline def contramapEither[B](f: B => Either[Throwable,A]): Observer[B] = Observer.contramapEither(sink)(f)
    @inline def contramapFilter[B](f: B => Option[A]): Observer[B] = Observer.contramapFilter(sink)(f)
    @inline def contracollect[B](f: PartialFunction[B, A]): Observer[B] = Observer.contracollect(sink)(f)
    @inline def contrafilter(f: A => Boolean): Observer[A] = Observer.contrafilter(sink)(f)
    @inline def contrascan[B](seed: A)(f: (A, B) => A): Observer[B] = Observer.contrascan(sink)(seed)(f)
    @inline def doOnError(f: Throwable => Unit): Observer[A] = Observer.doOnError(sink)(f)
    @inline def redirect[G[_] : Source, B](f: Observable[B] => G[A]): Observer.Connectable[B] = Observer.redirect(sink)(f)
  }

  private def recovered(action: => Unit, onError: Throwable => Unit): Unit = try action catch { case NonFatal(t) => onError(t) }
}
