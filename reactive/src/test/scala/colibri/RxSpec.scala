package colibri.reactive

import colibri._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class ReactiveSpec extends AsyncFlatSpec with Matchers {

  implicit def unsafeSubscriptionOwner[T]: SubscriptionOwner[T] = new SubscriptionOwner[T] {
    def own(owner: T)(subscription: () => Cancelable): T = {
      subscription()
      owner
    }
  }

  "Rx" should "have proper owner subscriptions" in {
    var mapped    = List.empty[Int]
    var received1 = List.empty[Int]
    var received2 = List.empty[Int]

    implicit val owner = Owner.unsafeRef()
    val subscription   = owner.unsafeSubscribe()

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x }

    mapped shouldBe List(1)
    received1 shouldBe List.empty
    received2 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(1)
    received2 shouldBe List.empty

    variable() = 2

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List.empty

    stream.foreach(received2 ::= _)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List(2)

    variable() = 3

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 2, 1)
    received2 shouldBe List(3, 2)

    variable() = 4

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    subscription.unsafeCancel()

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    variable() = 5

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    val subscription2 = owner.unsafeSubscribe()

    mapped shouldBe List(5, 4, 3, 2, 1)
    received1 shouldBe List(5, 4, 3, 2, 1)
    received2 shouldBe List(5, 4, 3, 2)

    variable() = 6

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    val subscription3 = owner.unsafeSubscribe()

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    variable() = 7

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    subscription2.unsafeCancel()

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    variable() = 8

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    subscription3.unsafeCancel()

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    variable() = 9

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)
  }

  it should "map with proper subscription lifetime" in Owned {
    var mapped    = List.empty[Int]
    var received1 = List.empty[Int]
    var received2 = List.empty[Int]

    val owner = implicitly[Owner]

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x }

    mapped shouldBe List(1)
    received1 shouldBe List.empty
    received2 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(1)
    received2 shouldBe List.empty

    variable() = 2

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List.empty

    stream.foreach(received2 ::= _)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List(2)

    variable() = 3

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 2, 1)
    received2 shouldBe List(3, 2)

    val cancel = owner.unsafeSubscribe()

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 2, 1)
    received2 shouldBe List(3, 2)

    variable() = 4

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    cancel.unsafeCancel()

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    variable() = 5

    mapped shouldBe List(5, 4, 3, 2, 1)
    received1 shouldBe List(5, 4, 3, 2, 1)
    received2 shouldBe List(5, 4, 3, 2)

    val cancel2 = owner.unsafeSubscribe()

    mapped shouldBe List(5, 4, 3, 2, 1)
    received1 shouldBe List(5, 4, 3, 2, 1)
    received2 shouldBe List(5, 4, 3, 2)

    variable() = 6

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    val _ = owner.unsafeSubscribe()

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    variable() = 7

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    cancel2.unsafeCancel()

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    variable() = 8

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    owner.cancelable.unsafeCancel()

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    variable() = 9

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)
  }.unsafeRunSync()

  it should "be distinct" in Owned {
    var mapped    = List.empty[Int]
    var received1 = List.empty[Boolean]

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x % 2 == 0 }

    mapped shouldBe List(1)
    received1 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(false)

    variable() = 2

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)

    variable() = 2

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)

    variable() = 4

    mapped shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)

    variable() = 5

    mapped shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
  }.unsafeRunSync()

  it should "be distinct in short cycle" in Owned {
    var mapped    = List.empty[Int]
    var received1 = List.empty[Boolean]

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x % 2 == 0 }

    stream.trigger(RxWriter { res =>
      if (!res) variable.setValue(0) else Fire.empty
    })

    mapped shouldBe List(0, 1)
    received1 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List(0, 1)
    received1 shouldBe List(true)

    variable() = 2

    mapped shouldBe List(2, 0, 1)
    received1 shouldBe List(true)

    variable() = 2

    mapped shouldBe List(2, 0, 1)
    received1 shouldBe List(true)

    variable() = 4

    mapped shouldBe List(4, 2, 0, 1)
    received1 shouldBe List(true)

    variable() = 5

    mapped shouldBe List(0, 5, 4, 2, 0, 1)
    received1 shouldBe List(true)
  }.unsafeRunSync()

  it should "work with multi update" in Owned {
    var liveCounter = 0
    var received1   = List.empty[String]

    val variable1 = Var("hallo")
    val variable2 = Var(2)

    received1 shouldBe List.empty

    val stream = Rx {
      liveCounter += 1
      variable1() + variable2()
    }

    liveCounter shouldBe 1
    received1 shouldBe List.empty

    stream.foreach(received1 ::= _)

    liveCounter shouldBe 1
    received1 shouldBe List("hallo2")

    RxWriter.update(
      variable1 -> "test",
      variable2 -> 15,
    )

    liveCounter shouldBe 2
    received1 shouldBe List("test15", "hallo2")

    RxWriter.update(
      variable1 -> "foo",
      variable1 -> "bar",
    )

    liveCounter shouldBe 3
    received1 shouldBe List("bar15", "test15", "hallo2")
  }.unsafeRunSync()

  it should "work nested" in Owned {
    var liveCounter  = 0
    var liveCounter2 = 0

    val variable  = Var(1)
    val variable2 = Var(2)

    val rx = Rx {
      liveCounter += 1

      val nested = Rx {
        liveCounter2 += 1
        variable2()
      }

      variable() + nested()
    }

    rx.now() shouldBe 3
    liveCounter shouldBe 1
    liveCounter2 shouldBe 1

    variable() = 2

    rx.now() shouldBe 4
    liveCounter shouldBe 2
    liveCounter2 shouldBe 2

    variable2() = 4

    rx.now() shouldBe 6
    liveCounter shouldBe 3
    liveCounter2 shouldBe 4 // TODO: why do we jump to 4 calculations here instead of 3?

    variable() = 3

    rx.now() shouldBe 7
    liveCounter shouldBe 4
    liveCounter2 shouldBe 5
  }.unsafeRunSync()

  it should "work with now" in Owned {
    var liveCounter = 0

    val variable  = Var(1)
    val variable2 = Var(2)
    val variable3 = Var(3)

    val rx = Rx {
      liveCounter += 1
      s"${variable()}, ${variable2()}, ${variable3.now()}"
    }

    rx.now() shouldBe "1, 2, 3"
    liveCounter shouldBe 1

    variable() = 2

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable() = 2

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable2() = 10

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable3() = 5

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable2() = 100

    rx.now() shouldBe "2, 100, 5"
    liveCounter shouldBe 4

  }.unsafeRunSync()

  it should "work with multi nesting" in Owned {
    var liveCounter = 0

    val variable  = Var(1)
    val variable2 = Var(2)
    val variable3 = Var(3)

    val rx = Owned {
      Owned {
        Rx {
          liveCounter += 1

          Owned {
            Rx {
              Rx {
                s"${variable()}, ${variable2()}, ${variable3.now()}"
              }
            }(implicitly)()
          }.unsafeRunSync()()
        }
      }.unsafeRunSync()
    }.unsafeRunSync()

    rx.now() shouldBe "1, 2, 3"
    liveCounter shouldBe 1

    variable() = 2

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable() = 2

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable2() = 10

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable3() = 5

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable2() = 100

    rx.now() shouldBe "2, 100, 5"
    liveCounter shouldBe 4

  }.unsafeRunSync()

  it should "work with switchMap" in Owned {
    var received1 = List.empty[Int]

    val variable1 = Var(1)
    val variable2 = Var(2)
    val variable3 = Var(3)

    val stream1 = variable1.switchMap {
      case 0 | 1 => variable2
      case _     => variable3
    }

    stream1.foreach(received1 ::= _)

    received1 shouldBe List(2)
    stream1.now() shouldBe 2

    variable1() = 0

    received1 shouldBe List(2)
    stream1.now() shouldBe 2

    variable2() = 10

    received1 shouldBe List(10, 2)
    stream1.now() shouldBe 10

    variable2() = 100

    received1 shouldBe List(100, 10, 2)
    stream1.now() shouldBe 100

    variable3() = 30

    received1 shouldBe List(100, 10, 2)
    stream1.now() shouldBe 100

    variable1() = 2

    received1 shouldBe List(30, 100, 10, 2)
    stream1.now() shouldBe 30

    variable2() = 1000

    received1 shouldBe List(30, 100, 10, 2)
    stream1.now() shouldBe 30

    variable3() = 300

    received1 shouldBe List(300, 30, 100, 10, 2)
    stream1.now() shouldBe 300

    variable3() = 300

    received1 shouldBe List(300, 30, 100, 10, 2)
    stream1.now() shouldBe 300

    variable1() = 2

    received1 shouldBe List(300, 30, 100, 10, 2)
    stream1.now() shouldBe 300

    variable1() = 4

    received1 shouldBe List(300, 30, 100, 10, 2)
    stream1.now() shouldBe 300

  }.unsafeRunSync()

  it should "work without glitches in chain" in Owned {
    var liveCounter = 0
    var mapped      = List.empty[Int]
    var received1   = List.empty[Boolean]
    var receivedRx  = List.empty[String]

    val variable = Var(1)

    val stream = variable.map { x => mapped ::= x; x % 2 == 0 }

    stream.foreach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(false)

    val rx = Rx {
      liveCounter += 1
      s"${variable()}: ${stream()}"
    }

    rx.foreach(receivedRx ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(false)
    receivedRx shouldBe List("1: false")
    rx.now() shouldBe "1: false"
    liveCounter shouldBe 1

    variable() = 2

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("2: true", "1: false")
    rx.now() shouldBe "2: true"
    liveCounter shouldBe 2

    variable() = 2

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("2: true", "1: false")
    rx.now() shouldBe "2: true"
    liveCounter shouldBe 2

    variable() = 4

    mapped shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("4: true", "2: true", "1: false")
    rx.now() shouldBe "4: true"
    liveCounter shouldBe 3

    variable() = 5

    mapped shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
    receivedRx shouldBe List("5: false", "4: true", "2: true", "1: false")
    rx.now() shouldBe "5: false"
    liveCounter shouldBe 4
  }.unsafeRunSync()

  it should "work without glitches in diamond" in Owned {
    var liveCounter = 0
    var mapped1     = List.empty[Int]
    var mapped2     = List.empty[Int]
    var received1   = List.empty[Boolean]
    var received2   = List.empty[Boolean]
    var receivedRx  = List.empty[String]

    val variable = Var(1)

    val stream1 = variable.map { x => mapped1 ::= x; x % 2 == 0 }
    val stream2 = variable.map { x => mapped2 ::= x; x % 2 == 0 }

    stream1.foreach(received1 ::= _)
    stream2.foreach(received2 ::= _)

    mapped1 shouldBe List(1)
    mapped2 shouldBe List(1)
    received1 shouldBe List(false)
    received2 shouldBe List(false)

    val rx = Rx {
      liveCounter += 1
      s"${stream1()}:${stream2()}"
    }

    rx.foreach(receivedRx ::= _)

    mapped1 shouldBe List(1)
    mapped2 shouldBe List(1)
    received1 shouldBe List(false)
    received2 shouldBe List(false)
    receivedRx shouldBe List("false:false")
    rx.now() shouldBe "false:false"
    liveCounter shouldBe 1

    variable() = 2

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "false:false")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 2

    variable() = 2

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "false:false")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 2

    variable() = 4

    mapped1 shouldBe List(4, 2, 1)
    mapped2 shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "false:false")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 2

    variable() = 5

    mapped1 shouldBe List(5, 4, 2, 1)
    mapped2 shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
    received2 shouldBe List(false, true, false)
    receivedRx shouldBe List("false:false", "true:true", "false:false")
    rx.now() shouldBe "false:false"
    liveCounter shouldBe 3
  }.unsafeRunSync()

  it should "work without glitches complex" in Owned {
    var liveCounter1 = 0
    var liveCounter2 = 0
    var liveCounter3 = 0
    var mapped1      = List.empty[Int]
    var mapped2      = List.empty[Int]
    var mapped3      = List.empty[Int]
    var received1    = List.empty[Boolean]
    var received2    = List.empty[Boolean]
    var received3    = List.empty[Int]
    var receivedRx1  = List.empty[String]
    var receivedRx2  = List.empty[String]
    var receivedRx3  = List.empty[String]

    val variable1 = Var(1)
    val variable2 = Var(2)

    val stream1 = variable1.filter(_ > 0)(0).map { x => mapped1 ::= x; x % 2 == 0 }
    val stream2 = variable1.map { x => mapped2 ::= x; x % 2 == 0 }
    val stream3 = variable2.map { x => mapped3 ::= x; x % 3 }.map(identity).map(identity).map(identity)

    stream1.foreach(received1 ::= _)
    stream2.foreach(received2 ::= _)
    stream3.foreach(received3 ::= _)

    mapped1 shouldBe List(1)
    mapped2 shouldBe List(1)
    mapped3 shouldBe List(2)
    received1 shouldBe List(false)
    received2 shouldBe List(false)
    received3 shouldBe List(2)

    val rx1 = Rx {
      liveCounter1 += 1
      s"${variable1()}:${stream1()}:${stream2()}:${stream3()}"
    }

    val rx2 = Rx {
      liveCounter2 += 1
      s"${rx1()}:${variable2()}"
    }

    val rx3 = Rx {
      liveCounter3 += 1
      rx2().split(":").drop(1).dropRight(2).mkString(":")
    }

    rx1.foreach(receivedRx1 ::= _)
    rx2.foreach(receivedRx2 ::= _)
    rx3.foreach(receivedRx3 ::= _)

    mapped1 shouldBe List(1)
    mapped2 shouldBe List(1)
    mapped3 shouldBe List(2)
    received1 shouldBe List(false)
    received2 shouldBe List(false)
    received3 shouldBe List(2)
    receivedRx1 shouldBe List("1:false:false:2")
    receivedRx2 shouldBe List("1:false:false:2:2")
    receivedRx3 shouldBe List("false:false")
    rx1.now() shouldBe "1:false:false:2"
    rx2.now() shouldBe "1:false:false:2:2"
    rx3.now() shouldBe "false:false"
    liveCounter1 shouldBe 1
    liveCounter2 shouldBe 1
    liveCounter3 shouldBe 1

    variable1() = 2

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    mapped3 shouldBe List(2)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    received3 shouldBe List(2)
    receivedRx1 shouldBe List("2:true:true:2", "1:false:false:2")
    receivedRx2 shouldBe List("2:true:true:2:2", "1:false:false:2:2")
    receivedRx3 shouldBe List("true:true", "false:false")
    rx1.now() shouldBe "2:true:true:2"
    rx2.now() shouldBe "2:true:true:2:2"
    rx3.now() shouldBe "true:true"
    liveCounter1 shouldBe 2
    liveCounter2 shouldBe 2
    liveCounter3 shouldBe 2

    variable1() = 2

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    mapped3 shouldBe List(2)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx1 shouldBe List("2:true:true:2", "1:false:false:2")
    receivedRx2 shouldBe List("2:true:true:2:2", "1:false:false:2:2")
    receivedRx3 shouldBe List("true:true", "false:false")
    rx1.now() shouldBe "2:true:true:2"
    rx2.now() shouldBe "2:true:true:2:2"
    rx3.now() shouldBe "true:true"
    liveCounter1 shouldBe 2
    liveCounter2 shouldBe 2
    liveCounter3 shouldBe 2

    variable1() = 4

    mapped1 shouldBe List(4, 2, 1)
    mapped2 shouldBe List(4, 2, 1)
    mapped3 shouldBe List(2)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx1 shouldBe List("4:true:true:2", "2:true:true:2", "1:false:false:2")
    receivedRx2 shouldBe List("4:true:true:2:2", "2:true:true:2:2", "1:false:false:2:2")
    receivedRx3 shouldBe List("true:true", "false:false")
    rx1.now() shouldBe "4:true:true:2"
    rx2.now() shouldBe "4:true:true:2:2"
    rx3.now() shouldBe "true:true"
    liveCounter1 shouldBe 3
    liveCounter2 shouldBe 3
    liveCounter3 shouldBe 3

    variable2() = 5

    mapped1 shouldBe List(4, 2, 1)
    mapped2 shouldBe List(4, 2, 1)
    mapped3 shouldBe List(5, 2)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx1 shouldBe List("4:true:true:2", "2:true:true:2", "1:false:false:2")
    receivedRx2 shouldBe List("4:true:true:2:5", "4:true:true:2:2", "2:true:true:2:2", "1:false:false:2:2")
    receivedRx3 shouldBe List("true:true", "false:false")
    rx1.now() shouldBe "4:true:true:2"
    rx2.now() shouldBe "4:true:true:2:5"
    rx3.now() shouldBe "true:true"
    liveCounter1 shouldBe 3
    liveCounter2 shouldBe 4
    liveCounter3 shouldBe 4

    variable2() = 6

    mapped1 shouldBe List(4, 2, 1)
    mapped2 shouldBe List(4, 2, 1)
    mapped3 shouldBe List(6, 5, 2)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx1 shouldBe List("4:true:true:0", "4:true:true:2", "2:true:true:2", "1:false:false:2")
    receivedRx2 shouldBe List("4:true:true:0:6", "4:true:true:2:5", "4:true:true:2:2", "2:true:true:2:2", "1:false:false:2:2")
    receivedRx3 shouldBe List("true:true", "false:false")
    rx1.now() shouldBe "4:true:true:0"
    rx2.now() shouldBe "4:true:true:0:6"
    rx3.now() shouldBe "true:true"
    liveCounter1 shouldBe 4
    liveCounter2 shouldBe 5
    liveCounter3 shouldBe 5

    variable1() = 5

    mapped1 shouldBe List(5, 4, 2, 1)
    mapped2 shouldBe List(5, 4, 2, 1)
    mapped3 shouldBe List(6, 5, 2)
    received1 shouldBe List(false, true, false)
    received2 shouldBe List(false, true, false)
    receivedRx1 shouldBe List("5:false:false:0", "4:true:true:0", "4:true:true:2", "2:true:true:2", "1:false:false:2")
    receivedRx2 shouldBe List(
      "5:false:false:0:6",
      "4:true:true:0:6",
      "4:true:true:2:5",
      "4:true:true:2:2",
      "2:true:true:2:2",
      "1:false:false:2:2",
    )
    receivedRx3 shouldBe List("false:false", "true:true", "false:false")
    rx1.now() shouldBe "5:false:false:0"
    rx2.now() shouldBe "5:false:false:0:6"
    rx3.now() shouldBe "false:false"
    liveCounter1 shouldBe 5
    liveCounter2 shouldBe 6
    liveCounter3 shouldBe 6

  }.unsafeRunSync()

  it should "work without glitches in short cycle" in Owned {
    var liveCounter = 0
    var mapped1     = List.empty[Int]
    var mapped2     = List.empty[Int]
    var received1   = List.empty[Boolean]
    var received2   = List.empty[Boolean]
    var receivedRx  = List.empty[String]

    val variable = Var(1)

    val stream1 = variable.map { x => mapped1 ::= x; x % 2 == 0 }
    val stream2 = variable.map { x => mapped2 ::= x; x % 2 == 0 }

    stream1.foreach(received1 ::= _)

    stream2.trigger(RxWriter { res =>
      if (!res) variable.setValue(0) else Fire.empty
    })

    stream2.foreach(received2 ::= _)

    mapped1 shouldBe List(0, 1)
    mapped2 shouldBe List(0, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true)

    val rx = Rx {
      liveCounter += 1
      s"${stream1()}:${stream2()}"
    }

    rx.foreach(receivedRx ::= _)

    mapped1 shouldBe List(0, 1)
    mapped2 shouldBe List(0, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true)
    receivedRx shouldBe List("true:true")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 1

    variable() = 2

    mapped1 shouldBe List(2, 0, 1)
    mapped2 shouldBe List(2, 0, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true)
    receivedRx shouldBe List("true:true")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 1

    variable() = 2

    mapped1 shouldBe List(2, 0, 1)
    mapped2 shouldBe List(2, 0, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true)
    receivedRx shouldBe List("true:true")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 1

    variable() = 4

    mapped1 shouldBe List(4, 2, 0, 1)
    mapped2 shouldBe List(4, 2, 0, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true)
    receivedRx shouldBe List("true:true")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 1

    variable() = 5

    mapped1 shouldBe List(0, 5, 4, 2, 0, 1)
    mapped2 shouldBe List(0, 5, 4, 2, 0, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true)
    receivedRx shouldBe List("true:true")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 1
  }.unsafeRunSync()
}
