package colibri.ext

import colibri._
import colibri.effect._
import cats.effect.Sync
import _root_.fs2.{Pure, Stream}

package object fs2 {

  @inline implicit def fs2StreamSource[F[_]: Sync: RunEffect]: Source[Stream[F, *]] = new SourceFs2[F]

  implicit object fs2StreamPureSource extends Source[Stream[Pure, *]] {
    override def unsafeSubscribe[A](source: Stream[Pure, A])(sink: Observer[A]): Cancelable = {
      source.attempt.map {
        case Right(value) => sink.unsafeOnNext(value)
        case Left(error) => sink.unsafeOnError(error)
      }.compile.drain

      Cancelable.empty
    }
  }
}

private final class SourceFs2[F[_]: Sync: RunEffect] extends Source[Stream[F, *]] {
  override def unsafeSubscribe[A](source: Stream[F, A])(sink: Observer[A]): Cancelable = {
    val effect = source.attempt.evalMap {
      case Right(value) => sink.onNextF[F](value)
      case Left(error) => sink.onErrorF(error)
    }.compile.drain

    RunEffect[F].unsafeRunSyncOrAsyncCancelable(effect) {_ => ()}
  }
}
