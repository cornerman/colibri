package colibri.helpers

// Taken from monix: https://github.com/monix/monix/blob/88fdaf67a11bffb8bf7a2d525f7564491574743e/monix-execution/shared/src/main/scala/monix/execution/internal/Newtype1.scala
private[colibri] abstract class Newtype1[F[_]] { self =>
  type Base
  trait Tag extends Any
  type Type[+A] <: Base with Tag

  def apply[A](fa: F[A]): Type[A] =
    fa.asInstanceOf[Type[A]]

  def unwrap[A](fa: Type[A]): F[A] =
    fa.asInstanceOf[F[A]]
}
