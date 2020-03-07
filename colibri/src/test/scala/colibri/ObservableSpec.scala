package colibri

import cats.effect.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class ObservableSpec extends AnyFlatSpec with Matchers {

  "Observable" should "map" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val stream = Observable.fromIterable(Seq(1,2,3)).map { x => mapped ::= x; x }

    mapped shouldBe List.empty

    stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1,3,2,1)
    received shouldBe List(3,2,1,3,2,1)
  }

  it should "scan" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val stream = Observable.fromIterable(Seq(1,2,3)).scan(0) { (a,x) => mapped ::= x; a + x }

    mapped shouldBe List.empty

    stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(6,3,1)
  }

  it should "dropWhile" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val stream = Observable.fromIterable(Seq(1,2,3,4)).dropWhile { x => mapped ::= x; x < 3 }

    mapped shouldBe List.empty

    stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(4,3)
  }

  it should "dropUntil" in {
    var received = List.empty[Int]
    val handler = Subject.behavior[Int](0)
    val until = Subject.replay[Unit]
    val stream = handler.dropUntil(until)

    stream.subscribe(Observer.create[Int](received ::= _))

    received shouldBe List()

    handler.onNext(1)

    received shouldBe List()

    until.onNext(())

    received shouldBe List()

    handler.onNext(2)

    received shouldBe List(2)

    handler.onNext(3)

    received shouldBe List(3,2)

    until.onNext(())

    received shouldBe List(3,2)

    handler.onNext(4)

    received shouldBe List(4,3,2)
  }

  it should "takeWhile" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val stream = Observable.fromIterable(Seq(1,2,3,4,5)).takeWhile { x => mapped ::= x; x < 3 }

    mapped shouldBe List.empty

    stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(2,1)
  }

  it should "takeUntil" in {
    var received = List.empty[Int]
    val handler = Subject.behavior[Int](0)
    val until = Subject.replay[Unit]
    val stream = handler.takeUntil(until)

    stream.subscribe(Observer.create[Int](received ::= _))

    received shouldBe List(0)

    handler.onNext(1)

    received shouldBe List(1,0)

    handler.onNext(2)

    received shouldBe List(2,1,0)

    until.onNext(())

    received shouldBe List(2,1,0)

    handler.onNext(3)

    received shouldBe List(2,1,0)

    handler.onNext(4)

    received shouldBe List(2,1,0)

    until.onNext(())

    received shouldBe List(2,1,0)

    handler.onNext(5)

    received shouldBe List(2,1,0)
  }

  it should "zip" in {
    var received = List.empty[(Int,String)]
    val handler = Subject.behavior[Int](0)
    val zipped = Subject.behavior[String]("a")
    val stream = handler.zip(zipped)

    val sub = stream.subscribe(Observer.create[(Int,String)](received ::= _))

    received shouldBe List((0, "a"))

    handler.onNext(1)

    received shouldBe List((0, "a"))

    handler.onNext(2)

    received shouldBe List((0, "a"))

    zipped.onNext("b")

    received shouldBe List((1, "b"), (0, "a"))

    zipped.onNext("c")

    received shouldBe List((2, "c"), (1, "b"), (0, "a"))

    zipped.onNext("d")

    received shouldBe List((2, "c"), (1, "b"), (0, "a"))

    handler.onNext(3)

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))

    sub.cancel()

    handler.onNext(4)

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))

    zipped.onNext("e")

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))
  }

  it should "combineLatest" in {
    var received = List.empty[(Int,String)]
    val handler = Subject.behavior[Int](0)
    val combined = Subject.behavior[String]("a")
    val stream = handler.combineLatest(combined)

    val sub = stream.subscribe(Observer.create[(Int,String)](received ::= _))

    received shouldBe List((0, "a"))

    handler.onNext(1)

    received shouldBe List((1, "a"), (0, "a"))

    handler.onNext(2)

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    combined.onNext("b")

    received shouldBe List((2, "b"), (2, "a"), (1, "a"), (0, "a"))

    combined.onNext("c")

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    sub.cancel()

    handler.onNext(3)

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    combined.onNext("d")

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))
  }

  it should "withLatest" in {
    var received = List.empty[(Int,String)]
    val handler = Subject.behavior[Int](0)
    val latest = Subject.behavior[String]("a")
    val stream = handler.withLatest(latest)

    val sub = stream.subscribe(Observer.create[(Int,String)](received ::= _))

    received shouldBe List((0, "a"))

    handler.onNext(1)

    received shouldBe List((1, "a"), (0, "a"))

    handler.onNext(2)

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    latest.onNext("b")

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    latest.onNext("c")

    received shouldBe List((2, "a"), (1, "a"), (0, "a"))

    handler.onNext(3)

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))

    sub.cancel()

    handler.onNext(3)

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))

    latest.onNext("d")

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))
  }

  it should "publish" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    val handler = Subject.replay[Int]
    val stream = Observable.merge(handler, Observable.fromIterable(Seq(1,2,3))).map { x => mapped ::= x; x }.publish.refCount

    mapped shouldBe List.empty

    val sub1 = stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    val sub2 = stream.subscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    handler.onNext(4)

    mapped shouldBe List(4,3,2,1)
    received shouldBe List(4,4,3,2,1)

    sub1.cancel()

    handler.onNext(5)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,2,1)

    sub2.cancel()

    handler.onNext(6)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,2,1)
  }

  it should "replay" in {
    var mapped = List.empty[Int]
    var received = List.empty[Int]
    var errors = 0
    val handler = Subject.replay[Int]
    val stream = Observable.merge(handler, Observable.fromIterable(Seq(1,2,3))).map { x => mapped ::= x; x }.replay.refCount

    mapped shouldBe List.empty

    val sub1 = stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,2,1)

    val sub2 = stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    mapped shouldBe List(3,2,1)
    received shouldBe List(3,3,2,1)

    handler.onNext(4)

    mapped shouldBe List(4,3,2,1)
    received shouldBe List(4,4,3,3,2,1)

    sub1.cancel()

    handler.onNext(5)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,3,2,1)

    sub2.cancel()

    handler.onNext(6)

    mapped shouldBe List(5,4,3,2,1)
    received shouldBe List(5,4,4,3,3,2,1)

    errors shouldBe 0

    val sub3 = stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    mapped shouldBe List(3,2,1,6,5,4,3,2,1)
    received shouldBe List(3,2,1,6,5,5,4,4,3,3,2,1)

    errors shouldBe 0

    handler.onError(new Exception)

    mapped shouldBe List(3,2,1,6,5,4,3,2,1)
    received shouldBe List(3,2,1,6,5,5,4,4,3,3,2,1)

    errors shouldBe 1

    handler.onNext(19)

    mapped shouldBe List(19,3,2,1,6,5,4,3,2,1)
    received shouldBe List(19,3,2,1,6,5,5,4,4,3,3,2,1)

    errors shouldBe 1

    sub3.cancel()

    mapped shouldBe List(19,3,2,1,6,5,4,3,2,1)
    received shouldBe List(19,3,2,1,6,5,5,4,4,3,3,2,1)

    errors shouldBe 1
  }

  it should "concatAsync" in {
    var runEffect = 0
    var received = List.empty[Int]
    var errors = 0
    val stream = Observable.concatAsync(IO { runEffect += 1; 0 }, Observable.fromIterable(Seq(1,2,3)))

    runEffect shouldBe 0

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    runEffect shouldBe 1
    received shouldBe List(3,2,1,0)
    errors shouldBe 0
  }

  it should "concatAsync neverending" in {
    var runEffect = List.empty[Int]
    var received = List.empty[Int]
    var errors = 0
    val stream = Observable.concatAsync(IO { runEffect ::= 0; 0 }, IO { runEffect ::= 1; 1 }, IO { runEffect ::= 2; 2 }, IO.never, IO { runEffect ::= 3; 3 })

    runEffect shouldBe List()

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    runEffect shouldBe List(2,1,0)
    received shouldBe List(2,1,0)
    errors shouldBe 0
  }

  it should "concatMapAsync" in {
    var received = List.empty[Int]
    var errors = 0
    val handler0 = IO(100)
    val handler1 = IO(200)
    val handler2 = IO(300)
    val handlers = Array(handler0, handler1, handler2)
    val stream = Observable.fromIterable(Seq(0,1,2)).concatMapAsync(handlers(_))

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    received shouldBe List(300,200,100)
    errors shouldBe 0
  }

  it should "switchVaried" in {
    var runEffect = 0
    var received = List.empty[Int]
    var errors = 0
    val stream = Observable.switchVaried(Observable.fromAsync(IO { runEffect += 1; 0 }), Observable.fromIterable(Seq(1,2,3)))

    runEffect shouldBe 0

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    runEffect shouldBe 1
    received shouldBe List(3,2,1,0)
    errors shouldBe 0
  }

  it should "switch" in {
    var received = List.empty[Int]
    var errors = 0
    val handler = Subject.behavior(0)
    val stream = Observable.switch(handler, Observable.fromIterable(Seq(1,2,3)))

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    received shouldBe List(3,2,1,0)
    errors shouldBe 0

    handler.onNext(19)

    received shouldBe List(3,2,1,0)
    errors shouldBe 0
  }

  it should "switchMap" in {
    var received = List.empty[Int]
    var errors = 0
    val handler0 = Subject.behavior[Int](0)
    val handler1 = Subject.replay[Int]
    val handler2 = Subject.behavior[Int](2)
    val handlers = Array(handler0, handler1, Observable.empty, handler2)
    val stream = Observable.fromIterable(Seq(0,1,2,3)).switchMap(handlers(_))

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    received shouldBe List(2,0)
    errors shouldBe 0

    handler0.onNext(19)

    received shouldBe List(2,0)
    errors shouldBe 0

    handler1.onNext(1)

    received shouldBe List(2,0)
    errors shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,2,0)
    errors shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,13,2,0)
    errors shouldBe 0

    handler1.onNext(2)

    received shouldBe List(13,13,2,0)
    errors shouldBe 0
  }

  it should "mergeVaried" in {
    var runEffect = 0
    var received = List.empty[Int]
    var errors = 0
    val stream = Observable.mergeVaried(Observable.fromAsync(IO { runEffect += 1; 0 }), Observable.fromIterable(Seq(1,2,3)))

    runEffect shouldBe 0

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    runEffect shouldBe 1
    received shouldBe List(3,2,1,0)
    errors shouldBe 0
  }

  it should "merge" in {
    var received = List.empty[Int]
    var errors = 0
    val handler = Subject.behavior(0)
    val handler2 = Subject.behavior(3)
    val stream = Observable.merge(handler, handler2)

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    received shouldBe List(3,0)
    errors shouldBe 0

    handler.onNext(19)

    received shouldBe List(19,3,0)
    errors shouldBe 0

    handler2.onNext(20)

    received shouldBe List(20,19,3,0)
    errors shouldBe 0

    handler2.onNext(21)

    received shouldBe List(21,20,19,3,0)
    errors shouldBe 0

    handler.onNext(39)

    received shouldBe List(39,21,20,19,3,0)
    errors shouldBe 0

    handler.onNext(-1)

    received shouldBe List(-1,39,21,20,19,3,0)
    errors shouldBe 0

    handler2.onNext(-1)

    received shouldBe List(-1,-1,39,21,20,19,3,0)
    errors shouldBe 0
  }

  it should "mergeMap" in {
    var received = List.empty[Int]
    var errors = 0
    val handler0 = Subject.behavior[Int](0)
    val handler1 = Subject.replay[Int]
    val handler2 = Subject.behavior[Int](2)
    val handlers = Array(handler0, handler1, handler2)
    val stream = Observable.fromIterable(Seq(0,1,2)).mergeMap(handlers(_))

    stream.subscribe(Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    ))

    received shouldBe List(2,0)
    errors shouldBe 0

    handler0.onNext(19)

    received shouldBe List(19,2,0)
    errors shouldBe 0

    handler1.onNext(1)

    received shouldBe List(1,19,2,0)
    errors shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,1,19,2,0)
    errors shouldBe 0

    handler2.onNext(13)

    received shouldBe List(13,13,1,19,2,0)
    errors shouldBe 0

    handler1.onNext(2)

    received shouldBe List(2,13,13,1,19,2,0)
    errors shouldBe 0
  }
}
