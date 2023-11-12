package colibri

import cats.effect.IO
import cats.effect.unsafe
import zio._
import colibri.ext.zio._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

import java.util.concurrent.TimeUnit

class ObservableSpec extends AsyncFlatSpec with Matchers {
  override val executionContext            = scala.scalajs.concurrent.QueueExecutionContext()
  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime(
    compute = this.executionContext,
    blocking = this.executionContext,
    config = unsafe.IORuntimeConfig(),
    scheduler = unsafe.IORuntime.defaultScheduler,
    shutdown = () => (),
  )

  "Observable" should "recover after mapEffect" in {
    var recovered      = List.empty[Throwable]
    var received       = List.empty[Unit]
    var receivedErrors = List.empty[Throwable]
    val exception      = new Exception("hallo")
    val stream         = Observable(()).mapEffect(_ => ZIO.fail(exception)).recover { case t => recovered ::= t }

    recovered shouldBe List.empty
    received shouldBe List.empty
    receivedErrors shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Unit](received ::= _, receivedErrors ::= _))

    recovered shouldBe List(exception)
    received shouldBe List(())
    receivedErrors shouldBe List.empty
  }

  it should "mapEffect" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler0 = ZIO.succeed(100)
    val handler1 = ZIO.succeed(200)
    val handler2 = ZIO.succeed(300)
    val handlers = Array(handler0, handler1, handler2)
    val stream   = Observable.fromIterable(Seq(0, 1, 2)).mapEffect(handlers(_))

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(300, 200, 100)
    errors shouldBe 0
  }

  it should "mapEffect RIO" in {
    var received = List.empty[Int]
    var errors   = 0
    val stream   = Observable(12).mapEffect(i => ZIO.sleep(100.millis).as(i))

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List()
    errors shouldBe 0
  }

  it should "lift" in {
    var received = List.empty[Int]
    var errors   = 0

    val scan   = zio.stream.ZStream(1, 2, 3, 4, 5).scan(0)(_ + _)
    val stream = Observable.lift(scan)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false
    received shouldBe List.empty
    errors shouldBe 0

    import scala.concurrent.duration._

    val test = for {
      _ <- IO.sleep(FiniteDuration.apply(0, TimeUnit.SECONDS))

      _ = received shouldBe List(15, 10, 6, 3, 1, 0)
      _ = errors shouldBe 0

      //TODO: why does it need an actual delay?
      _ <- IO.sleep(FiniteDuration.apply(10, TimeUnit.MILLISECONDS))
      _ = cancelable.isEmpty() shouldBe true
    } yield succeed

    test.unsafeToFuture()
  }
}
