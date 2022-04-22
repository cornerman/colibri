package colibri

import zio._
// import zio.stream.ZStream
import colibri.ext.zio._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class ObservableSpec extends AsyncFlatSpec with Matchers {

  "Observable" should "recover after mapEffect" in {
    var recovered      = List.empty[Throwable]
    var received       = List.empty[Unit]
    var receivedErrors = List.empty[Throwable]
    val exception      = new Exception("hallo")
    val stream         = Observable(()).mapEffect(_ => Task.fail(exception)).recover { case t => recovered ::= t }

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
    val handler0 = Task(100)
    val handler1 = Task(200)
    val handler2 = Task(300)
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
    import zio.duration._
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

    import scala.concurrent.duration._

    val duration    = 1.second
    val zioDuration = zio.duration.Duration.fromScala(duration)

    // val scan     = ZStream(1, 2, 3, 4, 5).concat(ZStream.never).scan(0)(_ + _)
    val scan2  = zio.stream.Stream.tick(zioDuration).as(1).scan[Int](0)(_ + _)
    val stream = Observable.lift(scan2)

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(15, 10, 6, 3, 1, 0)
    errors shouldBe 0
  }
}
