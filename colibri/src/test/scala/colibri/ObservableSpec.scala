package colibri

import cats.effect.{SyncIO, IO, Resource}
import cats.effect.unsafe
import cats.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

// import scala.concurrent.Future

class ObservableSpec extends AsyncFlatSpec with Matchers {
  override val executionContext            = scala.scalajs.concurrent.QueueExecutionContext()
  implicit val ioRuntime: unsafe.IORuntime = unsafe.IORuntime(
    compute = this.executionContext,
    blocking = this.executionContext,
    config = unsafe.IORuntimeConfig(),
    scheduler = unsafe.IORuntime.defaultScheduler,
    shutdown = () => (),
  )

  "Observable" should "map" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3)).map { x => mapped ::= x; x }

    mapped shouldBe List.empty

    val cancelable1 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)
    cancelable1.isEmpty() shouldBe true

    val cancelable2 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1, 3, 2, 1)
    received shouldBe List(3, 2, 1, 3, 2, 1)
    cancelable2.isEmpty() shouldBe true
  }

  it should "filter" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3)).filter { x => mapped ::= x; x == 2 }

    mapped shouldBe List.empty

    val cancelable1 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(2)
    cancelable1.isEmpty() shouldBe true

    val cancelable2 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1, 3, 2, 1)
    received shouldBe List(2, 2)
    cancelable2.isEmpty() shouldBe true
  }

  it should "discard" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3)).map { x => mapped ::= x; x }.discard

    mapped shouldBe List.empty

    val cancelable1 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List.empty
    cancelable1.isEmpty() shouldBe true

    val cancelable2 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1, 3, 2, 1)
    received shouldBe List.empty
    cancelable2.isEmpty() shouldBe true
  }

  it should "recover" in {
    var recovered      = List.empty[Throwable]
    var received       = List.empty[Unit]
    var receivedErrors = List.empty[Throwable]
    val exception      = new Exception("hallo")
    val stream         = Observable.raiseError(exception).recover { case t => recovered ::= t }

    recovered shouldBe List.empty
    received shouldBe List.empty
    receivedErrors shouldBe List.empty

    val cancelable = stream.unsafeSubscribe(Observer.create[Unit](received ::= _, receivedErrors ::= _))

    recovered shouldBe List(exception)
    received shouldBe List(())
    receivedErrors shouldBe List.empty
    cancelable.isEmpty() shouldBe true
  }

  it should "recover after mapEffect" in {
    var recovered      = List.empty[Throwable]
    var received       = List.empty[Unit]
    var receivedErrors = List.empty[Throwable]
    val exception      = new Exception("hallo")
    val stream         = Observable(()).mapEffect(_ => cats.effect.IO.raiseError(exception)).recover { case t => recovered ::= t }

    recovered shouldBe List.empty
    received shouldBe List.empty
    receivedErrors shouldBe List.empty

    val cancelable = stream.unsafeSubscribe(Observer.create[Unit](received ::= _, receivedErrors ::= _))

    recovered shouldBe List(exception)
    received shouldBe List(())
    receivedErrors shouldBe List.empty
    cancelable.isEmpty() shouldBe true
  }

  it should "scan" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3)).scan(0) { (a, x) => mapped ::= x; a + x }

    mapped shouldBe List.empty

    val cancelable = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(6, 3, 1)

    cancelable.isEmpty() shouldBe true
  }

  it should "switchScan" in {
    var recieved   = List.empty[Char]
    val source     = Subject.publish[Int]()
    val request1   = Subject.publish[Char]()
    val request2   = Subject.publish[Char]()
    val endTrigger = Subject.publish[Unit]()

    val stream = source.takeUntil(endTrigger).switchScan('0') {
      case (_, 1) => request1
      case (_, 2) => request2
      case _      => Observable.empty
    }

    val subscription = stream.unsafeSubscribe(Observer.create[Char](recieved ::= _))

    recieved shouldBe List.empty

    request1.unsafeOnNext('a')
    request2.unsafeOnNext('1')
    recieved shouldBe List.empty

    source.unsafeOnNext(1)
    recieved shouldBe List.empty
    request1.unsafeOnNext('b')
    request2.unsafeOnNext('2')
    recieved shouldBe List('b')
    request1.unsafeOnNext('c')
    request2.unsafeOnNext('3')
    recieved shouldBe List('c', 'b')

    subscription.isEmpty() shouldBe false

    subscription.unsafeCancel()

    subscription.isEmpty() shouldBe true

    request1.unsafeOnNext('z')
    recieved shouldBe List('c', 'b')

    val subscription2 = stream.unsafeSubscribe(Observer.create[Char](recieved ::= _))

    source.unsafeOnNext(2)
    recieved shouldBe List('c', 'b')
    request1.unsafeOnNext('d')
    request2.unsafeOnNext('4')
    recieved shouldBe List('4', 'c', 'b')
    request1.unsafeOnNext('e')
    request2.unsafeOnNext('5')
    recieved shouldBe List('5', '4', 'c', 'b')

    source.unsafeOnNext(3)
    recieved shouldBe List('5', '4', 'c', 'b')
    request1.unsafeOnNext('f')
    request2.unsafeOnNext('6')
    recieved shouldBe List('5', '4', 'c', 'b')

    subscription2.isEmpty() shouldBe false

    endTrigger.unsafeOnNext(())

    subscription2.isEmpty() shouldBe true
  }

  it should "dropWhile" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val stream   = Observable.fromIterable(Seq(1, 2, 3, 4)).dropWhile { x => mapped ::= x; x < 3 }

    mapped shouldBe List.empty

    val cancelable = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(4, 3)
    cancelable.isEmpty() shouldBe true
  }

  it should "dropUntil" in {
    var received = List.empty[Int]
    val handler  = Subject.behavior[Int](0)
    val until    = Subject.replayLatest[Unit]()
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

    val cancelable = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(2, 1)
    cancelable.isEmpty() shouldBe true
  }

  it should "takeUntil" in {
    var received = List.empty[Int]
    val handler  = Subject.behavior[Int](0)
    val until    = Subject.replayLatest[Unit]()
    val stream   = handler.takeUntil(until)

    val cancelable = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    cancelable.isEmpty() shouldBe false

    received shouldBe List(0)

    handler.unsafeOnNext(1)

    received shouldBe List(1, 0)

    handler.unsafeOnNext(2)

    received shouldBe List(2, 1, 0)

    cancelable.isEmpty() shouldBe false
    until.unsafeOnNext(())
    cancelable.isEmpty() shouldBe true

    received shouldBe List(2, 1, 0)

    handler.unsafeOnNext(3)

    received shouldBe List(2, 1, 0)

    handler.unsafeOnNext(4)

    received shouldBe List(2, 1, 0)

    until.unsafeOnNext(())
    cancelable.isEmpty() shouldBe true

    received shouldBe List(2, 1, 0)

    handler.unsafeOnNext(5)

    received shouldBe List(2, 1, 0)

    cancelable.isEmpty() shouldBe true
  }

  it should "zip" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val zipped   = Subject.behavior[String]("a")
    val stream   = handler.zip(zipped)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    sub.isEmpty() shouldBe false

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

    sub.isEmpty() shouldBe true

    handler.unsafeOnNext(4)

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))

    zipped.unsafeOnNext("e")

    received shouldBe List((3, "d"), (2, "c"), (1, "b"), (0, "a"))

    sub.isEmpty() shouldBe true
  }

  it should "combineLatest" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val combined = Subject.behavior[String]("a")
    val stream   = handler.combineLatest(combined)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    sub.isEmpty() shouldBe false

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

    sub.isEmpty() shouldBe true

    handler.unsafeOnNext(3)

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    combined.unsafeOnNext("d")

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    sub.isEmpty() shouldBe true
  }

  it should "parMapN" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val combined = Subject.behavior[String]("a")
    val stream   = (handler: Observable[Int], combined: Observable[String]).parMapN(_ -> _)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    sub.isEmpty() shouldBe false

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

    sub.isEmpty() shouldBe true

    handler.unsafeOnNext(3)

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    combined.unsafeOnNext("d")

    received shouldBe List((2, "c"), (2, "b"), (2, "a"), (1, "a"), (0, "a"))

    sub.isEmpty() shouldBe true
  }

  it should "withLatest" in {
    var received = List.empty[(Int, String)]
    val handler  = Subject.behavior[Int](0)
    val latest   = Subject.behavior[String]("a")
    val stream   = handler.withLatest(latest)

    val sub = stream.unsafeSubscribe(Observer.create[(Int, String)](received ::= _))

    sub.isEmpty() shouldBe false

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

    sub.isEmpty() shouldBe true

    handler.unsafeOnNext(3)

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))

    latest.unsafeOnNext("d")

    received shouldBe List((3, "c"), (2, "a"), (1, "a"), (0, "a"))
  }

  it should "publish" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    val handler  = Subject.replayLatest[Int]()
    val stream   = Observable.merge(handler, Observable.fromIterable(Seq(1, 2, 3))).map { x => mapped ::= x; x }.publish.refCount

    mapped shouldBe List.empty

    val sub1 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    sub1.isEmpty() shouldBe false

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    val sub2 = stream.unsafeSubscribe(Observer.create[Int](received ::= _))

    sub2.isEmpty() shouldBe false

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    handler.unsafeOnNext(4)

    mapped shouldBe List(4, 3, 2, 1)
    received shouldBe List(4, 4, 3, 2, 1)

    sub1.isEmpty() shouldBe false

    sub1.unsafeCancel()

    sub1.isEmpty() shouldBe true

    handler.unsafeOnNext(5)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 2, 1)

    sub2.isEmpty() shouldBe false

    sub2.unsafeCancel()

    sub2.isEmpty() shouldBe true

    handler.unsafeOnNext(6)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 2, 1)
  }

  it should "replayLatest" in {
    var mapped   = List.empty[Int]
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.replayLatest[Int]()
    val stream   = Observable.merge(handler, Observable.fromIterable(Seq(1, 2, 3))).map { x => mapped ::= x; x }.replayLatest.refCount

    mapped shouldBe List.empty

    val sub1 = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    sub1.isEmpty() shouldBe false

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 2, 1)

    val sub2 = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    sub2.isEmpty() shouldBe false

    mapped shouldBe List(3, 2, 1)
    received shouldBe List(3, 3, 2, 1)

    handler.unsafeOnNext(4)

    mapped shouldBe List(4, 3, 2, 1)
    received shouldBe List(4, 4, 3, 3, 2, 1)

    sub1.unsafeCancel()

    sub1.isEmpty() shouldBe true

    handler.unsafeOnNext(5)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received shouldBe List(5, 4, 4, 3, 3, 2, 1)

    sub2.unsafeCancel()

    sub2.isEmpty() shouldBe true

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

  it should "concatEffect foo" in {
    var runEffect = 0
    var received  = List.empty[Int]
    var errors    = 0
    val stream    = Observable.concatEffect(IO { runEffect += 1; 0 }, Observable.fromIterable(Seq(1, 2, 3)))

    runEffect shouldBe 0

    val sub = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    runEffect shouldBe 1
    received shouldBe List(3, 2, 1, 0)
    errors shouldBe 0
    sub.isEmpty() shouldBe true
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

    val sub = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    runEffect shouldBe List(2, 1, 0)
    received shouldBe List(2, 1, 0)
    errors shouldBe 0
    sub.isEmpty() shouldBe false
  }

  it should "fromResource" in {
    var received      = List.empty[Int]
    var errors        = 0
    var acquireCalls  = 0
    var finalizeCalls = 0
    val resource      = Resource.make(SyncIO { acquireCalls += 1 })(_ => SyncIO { finalizeCalls += 1 })
    val stream        = Observable.fromResource(resource).map(_ => 110)

    received shouldBe List.empty
    errors shouldBe 0
    acquireCalls shouldBe 0
    finalizeCalls shouldBe 0

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false

    received shouldBe List(110)
    errors shouldBe 0
    acquireCalls shouldBe 1
    finalizeCalls shouldBe 0

    cancelable.unsafeCancel()

    cancelable.isEmpty() shouldBe true

    received shouldBe List(110)
    errors shouldBe 0
    acquireCalls shouldBe 1
    finalizeCalls shouldBe 1

    cancelable.unsafeCancel()

    cancelable.isEmpty() shouldBe true

    received shouldBe List(110)
    errors shouldBe 0
    acquireCalls shouldBe 1
    finalizeCalls shouldBe 1
  }

  it should "mapResource" in {
    var received      = List.empty[Int]
    var acquireCalls  = List.empty[Int]
    var finalizeCalls = List.empty[Int]
    var errors        = 0

    def resource(i: Int) = Resource.make(SyncIO { acquireCalls ::= i })(_ => SyncIO { finalizeCalls ::= i }).as(i)
    val stream           = Observable(1, 2, 3).mapResource(resource)

    received shouldBe List.empty
    errors shouldBe 0
    acquireCalls shouldBe List.empty
    finalizeCalls shouldBe List.empty

    val observer = Observer.create[Int](
      received ::= _,
      _ => errors += 1,
    )

    val cancelable = stream.unsafeSubscribe(observer)

    cancelable.isEmpty() shouldBe false

    received shouldBe List(3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1)
    finalizeCalls shouldBe List.empty

    cancelable.unsafeCancel()

    cancelable.isEmpty() shouldBe true

    received shouldBe List(3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1)
    finalizeCalls shouldBe List(3, 2, 1)

    cancelable.unsafeCancel()

    cancelable.isEmpty() shouldBe true

    received shouldBe List(3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1)
    finalizeCalls shouldBe List(3, 2, 1)

    val cancelable2 = stream.unsafeSubscribe(observer)

    cancelable2.isEmpty() shouldBe false

    received shouldBe List(3, 2, 1, 3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1, 3, 2, 1)
    finalizeCalls shouldBe List(3, 2, 1)

    cancelable2.unsafeCancel()

    cancelable2.isEmpty() shouldBe true

    received shouldBe List(3, 2, 1, 3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1, 3, 2, 1)
    finalizeCalls shouldBe List(3, 2, 1, 3, 2, 1)
  }

  it should "switchMapResource" in {
    var received      = List.empty[Int]
    var acquireCalls  = List.empty[Int]
    var finalizeCalls = List.empty[Int]
    var errors        = 0

    def resource(i: Int) = Resource.make(SyncIO { acquireCalls ::= i })(_ => SyncIO { finalizeCalls ::= i }).as(i)
    val subject          = Subject.publish[Int]()
    val stream           = subject.switchMapResource(resource)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false

    received shouldBe List.empty
    errors shouldBe 0
    acquireCalls shouldBe List.empty
    finalizeCalls shouldBe List.empty

    subject.unsafeOnNext(1)

    received shouldBe List(1)
    errors shouldBe 0
    acquireCalls shouldBe List(1)
    finalizeCalls shouldBe List.empty

    subject.unsafeOnNext(2)

    received shouldBe List(2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(2, 1)
    finalizeCalls shouldBe List(1)

    subject.unsafeOnNext(3)

    received shouldBe List(3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1)
    finalizeCalls shouldBe List(2, 1)

    cancelable.unsafeCancel()

    cancelable.isEmpty() shouldBe true

    received shouldBe List(3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1)
    finalizeCalls shouldBe List(3, 2, 1)

    cancelable.unsafeCancel()

    cancelable.isEmpty() shouldBe true

    received shouldBe List(3, 2, 1)
    errors shouldBe 0
    acquireCalls shouldBe List(3, 2, 1)
    finalizeCalls shouldBe List(3, 2, 1)
  }

  it should "fromEffect" in {
    var received = List.empty[Int]
    var errors   = 0
    val effect   = IO(100)
    val stream   = Observable.fromEffect(effect)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe true
    received shouldBe List(100)
    errors shouldBe 0
  }

  it should "fromEffect cede" in {
    var received = List.empty[Int]
    var errors   = 0
    val effect   = IO(100)
    val stream   = Observable.fromEffect(IO.cede *> effect)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false
    received shouldBe Nil
    errors shouldBe 0

    val test = IO.cede *> IO {
      cancelable.isEmpty() shouldBe true
      received shouldBe List(100)
      errors shouldBe 0
    }

    test.unsafeToFuture()
  }

  it should "fromEffect sync" in {
    var received = List.empty[Int]
    var errors   = 0
    val effect   = SyncIO(100)
    val stream   = Observable.fromEffect(effect)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe true
    received shouldBe List(100)
    errors shouldBe 0
  }

  it should "mapEffect" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler0 = IO(100)
    val handler1 = IO(200)
    val handler2 = IO(300)
    val handlers = Array(handler0, handler1, handler2)
    val stream   = Observable.fromIterable(Seq(0, 1, 2)).mapEffect(handlers(_))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe true
    received shouldBe List(300, 200, 100)
    errors shouldBe 0
  }

  it should "switch" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.behavior(0)
    val stream   = Observable.switch(handler, Observable.fromIterable(Seq(1, 2, 3)))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe true

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
    val handler1 = Subject.replayLatest[Int]()
    val handler2 = Subject.behavior[Int](2)
    val handlers = Array(handler0, handler1, Observable.empty, handler2)
    val stream   = Observable.fromIterable(Seq(0, 1, 2, 3)).switchMap(handlers(_))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false

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

  it should "concatMap async complete" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prependEffect(IO.cede *> IO.pure(3))
    val observable3 = Observable.fromEffect(IO.cede *> IO.pure(6))
    val observable4 = Observable(7)
    val observable5 = Observable.empty
    val stream      = Observable(observable1, observable2, observable3, observable4, observable5).concatMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe false
      _ = received shouldBe List(2, 1)
      _ = errors shouldBe 0

      _ <- IO.cede *> IO.cede

      _ = cancelable.isEmpty() shouldBe false
      _ = received shouldBe List(5, 4, 3, 2, 1)
      _ = errors shouldBe 0

      _ <- IO.cede *> IO.cede *> IO.cede

      _ = cancelable.isEmpty() shouldBe false
      _ = received shouldBe List(6, 5, 4, 3, 2, 1)
      _ = errors shouldBe 0

      _ <- IO.cede

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(7, 6, 5, 4, 3, 2, 1)
      _ = errors shouldBe 0

    } yield succeed

    test.unsafeToFuture()
  }

  it should "mergeMap async complete" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prependEffect(IO.cede *> IO.pure(3))
    val observable3 = Observable.fromEffect(IO.cede *> IO.pure(6))
    val observable4 = Observable(7)
    val observable5 = Observable.empty
    val stream      = Observable(observable1, observable2, observable3, observable4, observable5).mergeMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe false
      _ = received shouldBe List(7, 2, 1)
      _ = errors shouldBe 0

      _ <- IO.cede *> IO.cede

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(6, 5, 4, 3, 7, 2, 1)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "switchMap async complete (is sync)" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prependEffect(IO.cede *> IO.pure(3))
    val observable3 = Observable.fromEffect(IO.cede *> IO.pure(6))
    val observable4 = Observable(7)
    val observable5 = Observable.empty
    val stream      = Observable(observable1, observable2, observable3, observable4, observable5).switchMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(7, 2, 1)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "switchMap async complete" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prependEffect(IO.cede *> IO.pure(3))
    val observable3 = Observable.fromEffect(IO.cede *> IO.pure(6))
    val observable4 = Observable.fromEffect(IO.cede *> IO.pure(7))
    val stream      = Observable(observable1, observable2, observable3, observable4).switchMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe false
      _ = received shouldBe List(2, 1)
      _ = errors shouldBe 0

      _ <- IO.cede *> IO.cede

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(7, 2, 1)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "concatMap sync complete" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prepend(3)
    val observable3 = Observable.pure(6)
    val observable4 = Observable(7)
    val stream      = Observable(observable1, observable2, observable3, observable4).concatMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(7, 6, 5, 4, 3, 2, 1)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "mergeMap sync complete" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prepend(3)
    val observable3 = Observable.pure(6)
    val observable4 = Observable(7)
    val stream      = Observable(observable1, observable2, observable3, observable4).mergeMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(7, 6, 5, 4, 3, 2, 1)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "switchMap sync complete" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2)
    val observable2 = Observable(4, 5).prepend(3)
    val observable3 = Observable.pure(6)
    val observable4 = Observable(7)
    val stream      = Observable(observable1, observable2, observable3, observable4).switchMap(identity)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(7, 6, 5, 4, 3, 2, 1)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "tailRecM" in {
    def sum[F[_]](numbers: List[F[Int]])(implicit m: cats.Monad[F]): F[Int] =
      m.tailRecM((numbers, 0)) { case (lst, accum) =>
        lst match {
          case Nil          => m.pure(Right(accum))
          case head :: tail =>
            head.map { h =>
              Left((tail, accum + h))
            }
        }
      }

    var received = List.empty[Int]
    var errors   = 0

    val iterations = 100000
    val stream     = sum((1 to iterations).map(Observable(_)).toList)

    import scala.concurrent.duration._

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe false
      _ = received shouldBe List.empty
      _ = errors shouldBe 0

      _ <- IO.sleep(0.1.seconds)

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List((iterations * (iterations + 1)) / 2)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "dropSyncGlitches" in {
    var received   = List.empty[Int]
    var errors     = 0
    val observable = Observable(1, 2)
    val stream     = observable.combineLatestMap(observable)(_ + _)

    val test = for {
      cancelable <- stream
                      .via(
                        Observer.create[Int](
                          received ::= _,
                          _ => errors += 1,
                        ),
                      )
                      .subscribeIO

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(4, 3)
      _ = errors shouldBe 0

      cancelable2 <- stream.dropSyncGlitches
                       .via(
                         Observer.create[Int](
                           received ::= _,
                           _ => errors += 1,
                         ),
                       )
                       .subscribeIO

      _ = cancelable2.isEmpty() shouldBe false
      _ = received shouldBe List(4, 3)
      _ = errors shouldBe 0

      _ <- IO.cede

      _ = cancelable.isEmpty() shouldBe true
      _ = received shouldBe List(4, 4, 3)
      _ = errors shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "headIO async" in {
    val head = Observable(1).prependEffect(IO.cede *> IO.pure(0)).headIO

    val test = for {
      value <- head

      _ = value shouldBe 0
    } yield succeed

    test.unsafeToFuture()
  }

  it should "headIO sync" in {
    val head = Observable(1, 2).headIO

    val test = for {
      value <- head

      _ = value shouldBe 1
    } yield succeed

    test.unsafeToFuture()
  }

  it should "lastIO async" in {
    val last = Observable(1).prependEffect(IO.cede *> IO.pure(0)).lastIO

    val test = for {
      value <- last

      _ = value shouldBe 1
    } yield succeed

    test.unsafeToFuture()
  }

  it should "lastIO sync" in {
    val last = Observable(1, 2).lastIO

    val test = for {
      value <- last

      _ = value shouldBe 2
    } yield succeed

    test.unsafeToFuture()
  }

  it should "lastIO async complex" in {
    val last = Observable(2)
      .prependEffect(IO.cede *> IO.pure(1))
      .concatMap(x => Observable(x, x).prependEffect(IO.cede *> IO.pure(0)))
      .take(100)
      .dropSyncAll
      .prepend(1000)
      .switchMap(x => Observable(x).delayMillis(40))
      .mergeMap(x => Observable(x).delayMillis(10))
      .distinctOnEquals
      .lastIO

    val test = for {
      value <- last

      _ = value shouldBe 2
    } yield succeed

    test.unsafeToFuture()
  }

  it should "syncLatest empty" in {
    val latest = Observable.empty.syncLatestSyncIO

    val value = latest.unsafeRunSync()
    value shouldBe None
  }

  it should "syncLatest sync" in {
    val latest = Observable(1, 2).syncLatestSyncIO

    val value = latest.unsafeRunSync()
    value shouldBe Some(2)
  }

  it should "syncLatest async" in {
    val latest = Observable(1).appendEffect(IO.cede *> IO.pure(2)).syncLatestSyncIO

    val value = latest.unsafeRunSync()
    value shouldBe Some(1)
  }

  it should "merge" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.behavior(0)
    val handler2 = Subject.behavior(3)
    val stream   = Observable.merge(handler, handler2)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false

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
    val handler1 = Subject.replayLatest[Int]()
    val handler2 = Subject.behavior[Int](2)
    val handlers = Array(handler0, handler1, handler2)
    val stream   = Observable.fromIterable(Seq(0, 1, 2)).mergeMap(handlers(_))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe false

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

  it should "compose isEmpty" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2, 3)
      .mapEffect(x => IO(x + 1))
      .mapEffect(x => SyncIO(x))
      .filter(_ > 2)
      .map(x => x + 1)
      .prepend(0)

    val observable2 = Observable.empty

    val observable3 = Observable.fromEffect(IO(0)).map(_ + 1)

    val stream = Observable.merge(observable1, observable2, observable3)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(1, 5, 4, 0)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe true
  }

  it should "compose isEmpty with async" in {
    var received    = List.empty[Int]
    var errors      = 0
    val observable1 = Observable(1, 2, 3) // .async
      .mapEffect(x => IO.cede *> IO(x + 1))
      .filter(_ > 2)
      // .mapFuture(x => Future(x + 1))
      .map(_ + 1)
      .evalOn(this.executionContext)
      .prepend(0)

    val observable2 = Observable.empty

    val observable3 = Observable.fromEffect(IO.cede *> IO(0)).map(_ + 1)

    val stream = Observable.merge(observable1, observable2, observable3)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(0)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    val test = IO.cede *> IO.defer {
      received shouldBe List(1, 0)
      errors shouldBe 0
      cancelable.isEmpty() shouldBe false

      List.fill(8)(IO.cede).sequence.map { _ =>
        received shouldBe List(5, 4, 1, 0)
        errors shouldBe 0
        cancelable.isEmpty() shouldBe true
      }
    }

    test.unsafeToFuture()
  }

  it should "not be empty if has task" in {
    var received = List.empty[Int]
    var errors   = 0
    val stream   = Observable(1, 2, 3).delayMillis(100)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List.empty
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false
  }

  it should "scanReduce" in {
    var received = List.empty[Int]
    var errors   = 0
    val stream   = Observable(1, 2, 3).scanReduce(_ + _)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(6, 3, 1)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe true
  }

  it should "mapFilterFirst" in {
    val result = Observable(1, 2, 3).mapFilterFirstIO(v => Option.when(v == 2)(v))

    val test = result.map { v =>
      v shouldBe 2
    }

    test.unsafeToFuture()
  }

  it should "mapFilterWhile" in {
    var received = List.empty[Int]
    var errors   = 0
    val stream   = Observable(1, 2, 3).mapFilterWhile(v => Option.when(v < 3)(v))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(2, 1)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe true
  }

  it should "forSemantic" in {
    var received = List.empty[Int]
    var errors   = 0
    val stream   = for {
      a <- Observable(1).concat(Observable(2).delayMillis(1)).forSwitch
      b <- Observable(10).concat(Observable(20).delayMillis(5)).forMerge
      c <- Observable(100).concat(Observable(200).delayMillis(10)).forConcat
      d <- Observable(1000).concat(Observable(2000).delayMillis(15))
    } yield a + b + c + d

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(1111)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    val test = stream.lastIO.map { last =>
      last shouldBe 2222
      received shouldBe List(2222, 2212, 1222, 2122, 1212, 2112, 1122, 1112, 1111)
      errors shouldBe 0
      cancelable.isEmpty() shouldBe true
    }

    test.unsafeToFuture()
  }

  it should "sampleWith" in {
    var received = List.empty[Int]
    var errors   = 0
    val trigger  = Subject.publish[Unit]()
    val subject  = Subject.publish[Int]()
    val stream   = subject.sampleWith(trigger)

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List()
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    trigger.unsafeOnNext(())

    received shouldBe List()
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    subject.unsafeOnNext(1)

    received shouldBe List()
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    trigger.unsafeOnNext(())

    received shouldBe List(1)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    trigger.unsafeOnNext(())

    received shouldBe List(1)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    subject.unsafeOnNext(2)
    subject.unsafeOnNext(3)

    received shouldBe List(1)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    trigger.unsafeOnNext(())

    received shouldBe List(3, 1)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false
  }

  it should "sampleWith initial" in {
    var received = List.empty[Int]
    var errors   = 0
    val trigger  = Subject.publish[Unit]()
    val subject  = Subject.publish[Int]()
    val stream   = subject.prepend(-100).sampleWith(trigger.prepend(()))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    received shouldBe List(-100)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    trigger.unsafeOnNext(())

    received shouldBe List(-100)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    subject.unsafeOnNext(1)

    received shouldBe List(-100)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    trigger.unsafeOnNext(())

    received shouldBe List(1, -100)
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false
  }

  it should "replaceWith" in {
    var mappedA  = List.empty[Unit]
    var mappedB  = List.empty[Unit]
    var received = List.empty[Unit]
    var errors   = 0
    val a        = Subject.publish[Unit]()
    val b        = Subject.publish[Unit]()
    val stream   = (a: Observable[Unit]).tap(mappedA ::= _).replaceWith((b: Observable[Unit]).tap(mappedB ::= _))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Unit](
        received ::= _,
        _ => errors += 1,
      ),
    )

    mappedA shouldBe List()
    mappedB shouldBe List()
    received shouldBe List()
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    a.unsafeOnNext(())

    mappedA shouldBe List(())
    mappedB shouldBe List()
    received shouldBe List(())
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    b.unsafeOnNext(())

    mappedA shouldBe List(())
    mappedB shouldBe List(())
    received shouldBe List((), ())
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false

    a.unsafeOnNext(())

    mappedA shouldBe List(())
    mappedB shouldBe List(())
    received shouldBe List((), ())
    errors shouldBe 0
    cancelable.isEmpty() shouldBe false
  }

  it should "replaceWith sync" in {
    var mappedA  = List.empty[Unit]
    var mappedB  = List.empty[Unit]
    var received = List.empty[Unit]
    var errors   = 0
    val stream   = Observable.pure(()).tap(mappedA ::= _).replaceWith(Observable.pure(()).tap(mappedB ::= _))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Unit](
        received ::= _,
        _ => errors += 1,
      ),
    )

    mappedA shouldBe List()
    mappedB shouldBe List(())
    received shouldBe List(())
    errors shouldBe 0
    cancelable.isEmpty() shouldBe true
  }

  it should "race" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.publish[Int]()
    val stream   = Observable.race(Observable.empty, handler, Observable(1, 2))

    val cancelable = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    cancelable.isEmpty() shouldBe true

    received shouldBe List(2, 1)
    errors shouldBe 0

    handler.unsafeOnNext(19)

    received shouldBe List(2, 1)
    errors shouldBe 0
  }

  it should "race and subject wins" in {
    var received = List.empty[Int]
    var errors   = 0
    val handler  = Subject.behavior[Int](0)
    val stream   = Observable.race(Observable.empty, handler, Observable(1, 2))

    val _ = stream.unsafeSubscribe(
      Observer.create[Int](
        received ::= _,
        _ => errors += 1,
      ),
    )

    // cancelable.isEmpty() shouldBe false

    received shouldBe List(0)
    errors shouldBe 0

    handler.unsafeOnNext(19)

    received shouldBe List(19, 0)
    errors shouldBe 0
  }
}
