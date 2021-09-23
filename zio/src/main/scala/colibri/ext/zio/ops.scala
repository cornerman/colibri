package colibri.ext.zio

import _root_.zio.stream.{Stream, UStream, ZSink, ZStream}
import _root_.zio.{Fiber, Chunk, Ref, Runtime, UIO, ZEnv, ZIO, URIO, IO, Task}
import colibri._

object ops {
  implicit class RichZStreamCompanion(val unused: ZStream.type) {
    def fromSink[R, E, A](fn: ZSink[R, E, A, A, Unit] => IO[E, Unit]): ZStream[R, E, A] = ZStream.effectAsyncM { emit =>
      fn(
        ZSink.foreach[Any, E, A](value => Task.fromFuture(_ => emit.single(value)).ignore)
          .foldM(error => ZSink.fromEffect(Task.fromFuture(_ => emit.fail(error)).ignore), _ => ZSink.drain)
      )
    }
  }

  implicit class RichSink[-R, +E, -A, +L, +Z](val stream: ZSink[R, E, A, L, Z]) extends AnyVal {
    def redirect
  }
}
