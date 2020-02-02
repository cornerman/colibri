package colibri

import cats.{MonoidK, Contravariant}

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
  @inline def connectable[T](sink: Observer[T], connect: () => Cancelable): Connectable[T] = new Connectable(sink, connect)

  def lift[F[_] : Sink, A](sink: F[A]): Observer[A] =  sink match {
    case sink: Observer[A@unchecked] => sink
    case _ => new Observer[A] {
      def onNext(value: A): Unit = Sink[F].onNext(sink)(value)
      def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
    }
  }

  @inline def createUnhandled[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = consume(value)
    def onError(error: Throwable): Unit = failure(error)
  }

  def create[A](consume: A => Unit, failure: Throwable => Unit = UnhandledErrorReporter.errorSubject.onNext): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = recovered(consume(value), onError)
    def onError(error: Throwable): Unit = failure(error)
  }

  @inline def combine[F[_] : Sink, A](sinks: F[A]*): Observer[A] = combineSeq(sinks)

  def combineSeq[F[_] : Sink, A](sinks: Seq[F[A]]): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = sinks.foreach(Sink[F].onNext(_)(value))
    def onError(error: Throwable): Unit = sinks.foreach(Sink[F].onError(_)(error))
  }

  def combineVaried[F[_] : Sink, G[_] : Sink, A](sinkA: F[A], sinkB: G[A]): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = {
      Sink[F].onNext(sinkA)(value)
      Sink[G].onNext(sinkB)(value)
    }
    def onError(error: Throwable): Unit = {
      Sink[F].onError(sinkA)(error)
      Sink[G].onError(sinkB)(error)
    }
  }

  def contramap[F[_] : Sink, A, B](sink: F[_ >: A])(f: B => A): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered(Sink[F].onNext(sink)(f(value)), onError)
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def contramapFilter[F[_] : Sink, A, B](sink: F[_ >: A])(f: B => Option[A]): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered(f(value).foreach(Sink[F].onNext(sink)), onError)
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def contracollect[F[_] : Sink, A, B](sink: F[_ >: A])(f: PartialFunction[B, A]): Observer[B] = new Observer[B] {
    def onNext(value: B): Unit = recovered({ f.runWith(Sink[F].onNext(sink))(value); () }, onError)
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def contrafilter[F[_] : Sink, A](sink: F[_ >: A])(f: A => Boolean): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = recovered(if (f(value)) Sink[F].onNext(sink)(value), onError)
    def onError(error: Throwable): Unit = Sink[F].onError(sink)(error)
  }

  def doOnError[F[_] : Sink, A](sink: F[_ >: A])(f: Throwable => Unit): Observer[A] = new Observer[A] {
    def onNext(value: A): Unit = Sink[F].onNext(sink)(value)
    def onError(error: Throwable): Unit = f(error)
  }

  def redirect[F[_] : Sink, G[_] : Source, A, B](sink: F[_ >: A])(transform: Observable[B] => G[A]): Connectable[B] = {
    val handler = Subject.publish[B]
    val source = transform(handler)
    connectable(handler, () => Source[G].subscribe(source)(sink))
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
    @inline def contramapFilter[B](f: B => Option[A]): Observer[B] = Observer.contramapFilter(sink)(f)
    @inline def contracollect[B](f: PartialFunction[B, A]): Observer[B] = Observer.contracollect(sink)(f)
    @inline def contrafilter(f: A => Boolean): Observer[A] = Observer.contrafilter(sink)(f)
    @inline def doOnError(f: Throwable => Unit): Observer[A] = Observer.doOnError(sink)(f)
    @inline def redirect[F[_] : Source, B](f: Observable[B] => F[A]): Observer.Connectable[B] = Observer.redirect(sink)(f)
  }

  @inline private def recovered(action: => Unit, onError: Throwable => Unit): Unit = try action catch { case NonFatal(t) => onError(t) }
}
