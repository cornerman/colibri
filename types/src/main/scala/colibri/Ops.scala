package colibri

import scala.concurrent.duration.FiniteDuration

trait ZipSource[F[_]] {
  def zipMap[A, B, R](sourceA: F[A], sourceB: F[B])(f: (A, B) => R): F[R]
}
object ZipSource {
  @inline def apply[F[_]](implicit source: ZipSource[F]): ZipSource[F] = source

  @inline implicit class Ops[F[_]](val source: ZipSource[F]) extends AnyVal {
    def zip[A, B](sourceA: F[A], sourceB: F[B]): F[(A,B)] = source.zipMap(sourceA, sourceB)(_ -> _)
  }
}

trait ConcatSource[F[_]] {
  def concatMap[A, B](source: F[A])(f: A => F[B]): F[B]
  // def concat[A](sourceA: F[A], sourceB: F[A]): F[A]
}
object ConcatSource {
  @inline def apply[F[_]](implicit source: ConcatSource[F]): ConcatSource[F] = source
}

trait MergeSource[F[_]] {
  def mergeMap[A, B](source: F[A])(f: A => F[B]): F[B]
  // def merge[A](sourceA: F[A], sourceB: F[A]): F[A]
}
object MergeSource {
  @inline def apply[F[_]](implicit source: MergeSource[F]): MergeSource[F] = source
}

trait CombineLatestSource[F[_]] {
  def combineLatestMap[A, B, R](sourceA: F[A], sourceB: F[B])(f: (A, B) => R): F[R]
}
object CombineLatestSource {
  @inline def apply[F[_]](implicit source: CombineLatestSource[F]): CombineLatestSource[F] = source

  @inline implicit class Ops[F[_]](val source: CombineLatestSource[F]) extends AnyVal {
    def combineLatest[A, B](sourceA: F[A], sourceB: F[B]): F[(A,B)] = source.combineLatestMap(sourceA, sourceB)(_ -> _)
  }
}

trait WithLatestSource[F[_]] {
  def withLatestMap[A, B, R](sourceA: F[A], sourceB: F[B])(f: (A, B) => R): F[R]
}
object WithLatestSource {
  @inline def apply[F[_]](implicit source: WithLatestSource[F]): WithLatestSource[F] = source

  @inline implicit class Ops[F[_]](val source: WithLatestSource[F]) extends AnyVal {
    def withLatest[A, B](sourceA: F[A], sourceB: F[B]): F[(A,B)] = source.withLatestMap(sourceA, sourceB)(_ -> _)
  }
}

trait SwitchSource[F[_]] {
  def switchMap[A, B](source: F[A])(f: A => F[B]): F[B]
  // def switch[A](sourceA: F[A], sourceB: F[A]): F[A]
}
object SwitchSource {
  @inline def apply[F[_]](implicit source: SwitchSource[F]): SwitchSource[F] = source
}

trait DebounceSource[F[_]] {
  def debounce[A](source: F[A])(duration: FiniteDuration): F[A]
}
object DebounceSource {
  @inline def apply[F[_]](implicit source: DebounceSource[F]): DebounceSource[F] = source
}

trait SampleSource[F[_]] {
  def sample[A](source: F[A])(duration: FiniteDuration): F[A]
}
object SampleSource {
  @inline def apply[F[_]](implicit source: SampleSource[F]): SampleSource[F] = source
}
