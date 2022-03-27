package colibri

import cats.effect.IO
import cats.effect.unsafe
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class ObservableSpec extends AsyncFlatSpec with Matchers {
  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime(
    compute = this.executionContext,
    blocking = this.executionContext,
    config = unsafe.IORuntimeConfig(),
    scheduler = unsafe.IORuntime.defaultScheduler,
    shutdown = () => ()
  )

  "Observable" should "map" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3)).map { x => mapped ::= x; x }

    mapped shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1, 3, 2, 1)
    received shouldBe List(3, 2, 1, 3, 2, 1)
  }

  it should "recover" in {
    var recovered = List.empty[Throwable]
    var received = List.empty[Unit]
    var receivedErrors = List.empty[Throwable]
    val exception = new Exception("hallo")
    val stream   = Observable.raiseError(exception).recover { case t => recovered ::= t }

    recovered shouldBe List.empty
    received shouldBe List.empty
    receivedErrors shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Unit](received ::= _, receivedErrors ::= _))

    recovered shouldBe List(exception)
    received shouldBe List(())
    receivedErrors shouldBe List.empty
  }

  it should "recover after mapEffect" in {
    var recovered = List.empty[Throwable]
    var received = List.empty[Unit]
    var receivedErrors = List.empty[Throwable]
    val exception = new Exception("hallo")
    val stream   = Observable(()).mapEffect(_ => cats.effect.IO.raiseError(exception)).recover { case t => recovered ::= t }

    recovered shouldBe List.empty
    received shouldBe List.empty
    receivedErrors shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Unit](received ::= _, receivedErrors ::= _))

    recovered shouldBe List(exception)
    received shouldBe List(())
    receivedErrors shouldBe List.empty
  }

  it should "scan" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3)).scan(0) { (a, x) => mapped ::= x; a + x }

    mapped shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(6, 3, 1)
  }

  it should "dropWhile" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3, 4)).dropWhile { x => mapped ::= x; x < 3 }

    mapped shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(4, 3)
  }

  it should "dropUntil" in {
    var received = List.empty[Int]
    val handler  = Subject.behavior[Int](0)
    val until    = Subject.replay[Unit]()
    val stream   = handler.dropUntil(until)

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    received shouldBe List()

    handler.unsafeOnNext(1)

    received shouldBe List()

    until.unsafeOnNext(())

    received shouldBe List()

    handler.unsafeOnNext(2)

    received shouldBe List(2)

    handler.unsafeOnNext(3)

    received shouldBe List(3, 2)

    until.unsafeOnNext(())

    received shouldBe List(3, 2)

    handler.unsafeOnNext(4)

    received shouldBe List(4, 3, 2)
  }

  it should "takeWhile" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3, 4, 5)).takeWhile { x => mapped ::= x; x < 3 }

    mapped shouldBe List.empty

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(2, 1)
  }

  it should "takeUntil" in {
    var received = List.empty[Int]
    val handler  = Subject.behavior[Int](0)
    val until    = Subject.replay[Unit]()
    val stream   = handler.takeUntil(until)

    stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    received shouldBe List(0)

    handler.unsafeOnNext(1)

    received shouldBe List(1, 0)

    handler.unsafeOnNext(2)

    received shouldBe List(2, 1, 0)

    until.unsafeOnNext(())

    received shouldBe List(2, 1, 0)

    handler.unsafeOnNext(3)

    received shouldBe List(2, 1, 0)

    handler.unsafeOnNext(4)

    received shouldBe List(2, 1, 0)

    until.unsafeOnNext(())

    received shouldBe List(2, 1, 0)

    handler.unsafeOnNext(5)

    received shouldBe List(2, 1, 0)
  }

  it should "zip" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val zipped   = Subject.behavior[String]("a")
    val stream   = handler.zip(zipped)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    received shouldBe List((0, "a"))

    handler.unsafeOnNext(1)

    received shouldBe List((0, "a"))

    handler.unsafeOnNext(2)

    received shouldBe List((0, "a"))

    zipped.unsafeOnNext("b")

    received shouldBe List((1, "b"), (0, "a"))

    zipped.unsafeOnNext("c")

    received shouldBe List((2, "c"), (1, "b"), (0, "a"))

    zipped.unsafeOnNext("d")

    received shouldBe List((2, "c"), (1, "b"), (0, "a"))

    handler.unsafeOnNext(3)

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))

    sub.unsafeCancel()

    handler.unsafeOnNext(4)

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))

    zipped.unsafeOnNext("e")

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))
  }

  it should "combineLatest" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val combined = Subject.behavior[String]("a")
    val stream   = handler.combineLatest(combined)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    received shouldBe List((0, "a"))

    handler.unsafeOnNext(1)

    received shouldBe List((1, "a"), (0, "a"))

    handler.unsafeOnNext(2)

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    combined.unsafeOnNext("b")

    received shouldBe List((2, "b"), (2, "a"), (1, "a"), (0, "a"))

    combined.unsafeOnNext("c")

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    sub.unsafeCancel()

    handler.unsafeOnNext(3)

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    combined.unsafeOnNext("d")

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))
  }

  it should "withLatest" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val latest   = Subject.behavior[String]("a")
    val stream   = handler.withLatest(latest)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    received shouldBe List((0, "a"))

    handler.unsafeOnNext(1)

    received shouldBe List((1, "a"), (0, "a"))

    handler.unsafeOnNext(2)

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    latest.unsafeOnNext("b")

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    latest.unsafeOnNext("c")

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    handler.unsafeOnNext(3)

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))

    sub.unsafeCancel()

    handler.unsafeOnNext(3)

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))

    latest.unsafeOnNext("d")

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))
  }

  it should "publish" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val handler  = Subject.replay[Int]()
    val stream   = Observable.merge(handler, Observable.fromIterable(Seq(1, 2, 3))).map { x => mapped ::= x; x }.publish.refCount

    mapped shouldBe List.empty

    val sub1 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    val sub2 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    handler.unsafeOnNext(4)

    mapped shouldBe List(4, 3, 2, 1)
    received shouldBe List(4, 4, 3, 2, 1)

    sub1.unsafeCancel()

    handler.unsafeOnNext(5)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 2, 1)

    sub2.unsafeCancel()

    handler.unsafeOnNext(6)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 2, 1)
  }

  it should "replay" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.replay[Int]()
    val stream   = Observable.merge(handler, Observable.fromIterable(Seq(1, 2, 3))).map { x => mapped ::= x; x }.replay.refCount

    mapped shouldBe List.empty

    val sub1 = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    val sub2 = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 3, 2, 1)

    handler.unsafeOnNext(4)

    mapped shouldBe List(4, 3, 2, 1)
    received shouldBe List(4, 4, 3, 3, 2, 1)

    sub1.unsafeCancel()

    handler.unsafeOnNext(5)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 3, 2, 1)

    sub2.unsafeCancel()

    handler.unsafeOnNext(6)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 3, 2, 1)

    errors shouldBe 0

    val sub3 = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    mapped shouldBe List(3, 2, 1, 6, 5, 4, 3, 2, 1)
    received shouldBe List(3, 2, 1, 6, 5, 5, 4, 4, 3, 3, 2, 1)

    errors shouldBe 0

    handler.unsafeOnError(new Exception)

    mapped shouldBe List(3, 2, 1, 6, 5, 4, 3, 2, 1)
    received shouldBe List(3, 2, 1, 6, 5, 5, 4, 4, 3, 3, 2, 1)

    errors shouldBe 1

    handler.unsafeOnNext(19)

    mapped shouldBe List(19, 3, 2, 1, 6, 5, 4, 3, 2, 1)
    received shouldBe List(19, 3, 2, 1, 6, 5, 5, 4, 4, 3, 3, 2, 1)

    errors shouldBe 1

    sub3.unsafeCancel()

    mapped shouldBe List(19, 3, 2, 1, 6, 5, 4, 3, 2, 1)
    received shouldBe List(19, 3, 2, 1, 6, 5, 5, 4, 4, 3, 3, 2, 1)

    errors shouldBe 1
  }

  it should "concatEffect" in {
    var runEffect = 0
    var received  = List.empty[Int]
    var errors    = 0
    val stream    = Observable.concatEffect(IO { runEffect += 1; 0 }, Observable.fromIterable(Seq(1, 2, 3)))

    runEffect shouldBe 0

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    runEffect shouldBe 1
    received shouldBe List(3, 2, 1, 0)
    errors shouldBe 0
  }

  it should "concatEffect neverending" in {
    var runEffect = List.empty[Int]
    var received  = List.empty[Int]
    var errors    = 0
    val stream    = Observable.concatEffect(
      IO { runEffect ::= 0; 0 },
      IO { runEffect ::= 1; 1 },
      IO { runEffect ::= 2; 2 },
      IO.never,
      IO { runEffect ::= 3; 3 },
    )

    runEffect shouldBe List()

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    runEffect shouldBe List(2, 1, 0)
    received shouldBe List(2, 1, 0)
    errors shouldBe 0
  }

  it should "fromEffect" in {
    var received = List.empty[Int]
    var errors   = 0
    val effect = IO(100)
    val stream   = Observable.fromEffect(effect)

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(100)
    errors shouldBe 0
  }

  it should "fromEffect cede" in {
    var received = List.empty[Int]
    var errors   = 0
    val effect = IO(100)
    val stream   = Observable.fromEffect(IO.cede *> effect)

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe Nil
    errors shouldBe 0

    val test = IO.cede *> IO {
      received shouldBe List(100)
      errors shouldBe 0
    }

    test.unsafeToFuture()
  }

  it should "mapEffect" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler0 = IO(100)
    val handler1 = IO(200)
    val handler2 = IO(300)
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

  it should "switch" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.behavior(0)
    val stream   = Observable.switch(handler, Observable.fromIterable(Seq(1, 2, 3)))

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(3, 2, 1, 0)
    errors shouldBe 0

    handler.unsafeOnNext(19)

    received shouldBe List(3, 2, 1, 0)
    errors shouldBe 0
  }

  it should "switchMap" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler0 = Subject.behavior[Int](0)
    val handler1 = Subject.replay[Int]()
    val handler2 = Subject.behavior[Int](2)
    val handlers = Array(handler0, handler1, Observable.empty, handler2)
    val stream   = Observable.fromIterable(Seq(0, 1, 2, 3)).switchMap(handlers(_))

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(2, 0)
    errors shouldBe 0

    handler0.unsafeOnNext(19)

    received shouldBe List(2, 0)
    errors shouldBe 0

    handler1.unsafeOnNext(1)

    received shouldBe List(2, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(13)

    received shouldBe List(13, 2, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(13)

    received shouldBe List(13, 13, 2, 0)
    errors shouldBe 0

    handler1.unsafeOnNext(2)

    received shouldBe List(13, 13, 2, 0)
    errors shouldBe 0
  }

  it should "merge" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.behavior(0)
    val handler2 = Subject.behavior(3)
    val stream   = Observable.merge(handler, handler2)

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(3, 0)
    errors shouldBe 0

    handler.unsafeOnNext(19)

    received shouldBe List(19, 3, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(20)

    received shouldBe List(20, 19, 3, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(21)

    received shouldBe List(21, 20, 19, 3, 0)
    errors shouldBe 0

    handler.unsafeOnNext(39)

    received shouldBe List(39, 21, 20, 19, 3, 0)
    errors shouldBe 0

    handler.unsafeOnNext(-1)

    received shouldBe List(-1, 39, 21, 20, 19, 3, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(-1)

    received shouldBe List(-1, -1, 39, 21, 20, 19, 3, 0)
    errors shouldBe 0
  }

  it should "mergeMap" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler0 = Subject.behavior[Int](0)
    val handler1 = Subject.replay[Int]()
    val handler2 = Subject.behavior[Int](2)
    val handlers = Array(handler0, handler1, handler2)
    val stream   = Observable.fromIterable(Seq(0, 1, 2)).mergeMap(handlers(_))

    stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(2, 0)
    errors shouldBe 0

    handler0.unsafeOnNext(19)

    received shouldBe List(19, 2, 0)
    errors shouldBe 0

    handler1.unsafeOnNext(1)

    received shouldBe List(1, 19, 2, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(13)

    received shouldBe List(13, 1, 19, 2, 0)
    errors shouldBe 0

    handler2.unsafeOnNext(13)

    received shouldBe List(13, 13, 1, 19, 2, 0)
    errors shouldBe 0

    handler1.unsafeOnNext(2)

    received shouldBe List(2, 13, 13, 1, 19, 2, 0)
    errors shouldBe 0
  }
}
