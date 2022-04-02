package colibri

import cats.effect.{IO, SyncIO, unsafe}
import fs2.{Pure, Stream}
import colibri.ext.fs2._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class ObservableSpec extends AsyncFlatSpec with Matchers {

  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime(
    compute = this.executionContext,
    blocking = this.executionContext,
    config = unsafe.IORuntimeConfig(),
    scheduler = unsafe.IORuntime.defaultScheduler,
    shutdown = () => (),
  )

  "Observable" should "work with pure" in {
    var mapped                    = List.empty[Int]
    var received                  = List.empty[Int]
    val stream: Stream[Pure, Int] = Stream.emits(Seq(1, 2, 3)).map { x => mapped ::= x; x }

    mapped shouldBe (List.empty)

    Source[Stream[Pure, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    Source[Stream[Pure, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1, 3, 2, 1)
    received shouldBe List(3, 2, 1, 3, 2, 1)
  }

  it should "work with error" in {
    var received                    = List.empty[Int]
    var receivedThrowable           = List.empty[Throwable]
    val ex                          = new Exception("HI")
    val stream: Stream[SyncIO, Int] = Stream.raiseError[SyncIO](ex)

    Source[Stream[SyncIO, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _, receivedThrowable ::= _))

    received shouldBe List.empty
    receivedThrowable shouldBe List(ex)

    Source[Stream[SyncIO, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _, receivedThrowable ::= _))

    received shouldBe List.empty
    receivedThrowable shouldBe List(ex, ex)
  }

  it should "work with effect sync" in {
    var mapped                      = List.empty[Int]
    var received                    = List.empty[Int]
    val stream: Stream[SyncIO, Int] = Stream.eval(SyncIO(1)).map { x => mapped ::= x; x }

    mapped shouldBe List.empty

    Source[Stream[SyncIO, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _))

    mapped shouldBe List(1)
    received shouldBe List(1)

    Source[Stream[SyncIO, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _))

    mapped shouldBe List(1, 1)
    received shouldBe List(1, 1)
  }

  it should "work with effect async" in {
    var mapped                  = List.empty[Int]
    var received                = List.empty[Int]
    val stream: Stream[IO, Int] = Stream.eval(IO(1)).map { x => mapped ::= x; x }

    mapped shouldBe List.empty

    Source[Stream[IO, *]].unsafeSubscribe(stream)(Observer.create[Int](received ::= _))

    mapped shouldBe List.empty
    received shouldBe List.empty

    val test = IO.cede *> IO {
      mapped shouldBe List(1)
      received shouldBe List(1)
    }

    test.unsafeToFuture()
  }
}
