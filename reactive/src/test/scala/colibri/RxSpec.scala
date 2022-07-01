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

  "Rx" should "map with proper subscription lifetime" in Owned {
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

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List.empty

    stream.foreach(received2 ::= _)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List(2)

    variable.set(3)

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 2, 1)
    received2 shouldBe List(3, 2)

    val cancel = owner.unsafeSubscribe()

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 2, 1)
    received2 shouldBe List(3, 2)

    variable.set(4)

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    cancel.unsafeCancel()

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    variable.set(5)

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    val cancel2 = owner.unsafeSubscribe()

    mapped shouldBe List(5, 4, 3, 2, 1)
    received1 shouldBe List(5, 4, 3, 2, 1)
    received2 shouldBe List(5, 4, 3, 2)

    variable.set(6)

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    val cancel3 = owner.unsafeSubscribe()

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    variable.set(7)

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    cancel2.unsafeCancel()

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    variable.set(8)

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    cancel3.unsafeCancel()

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    variable.set(9)

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

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)

    variable.set(4)

    mapped shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)

    variable.set(5)

    mapped shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
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

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("2: true", "1: false")
    rx.now() shouldBe "2: true"
    liveCounter shouldBe 2

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("2: true", "1: false")
    rx.now() shouldBe "2: true"
    liveCounter shouldBe 2

    variable.set(4)

    mapped shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("4: true", "2: true", "1: false")
    rx.now() shouldBe "4: true"
    liveCounter shouldBe 3

    variable.set(5)

    mapped shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
    receivedRx shouldBe List("5: false", "4: true", "2: true", "1: false")
    rx.now() shouldBe "5: false"
    liveCounter shouldBe 4
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

    variable.set(2)

    rx.now() shouldBe 4
    liveCounter shouldBe 2
    liveCounter2 shouldBe 2

    variable2.set(4)

    rx.now() shouldBe 6
    liveCounter shouldBe 3
    liveCounter2 shouldBe 4 // TODO: why do we jump to 4 calculations here instead of 3?

    variable.set(3)

    rx.now() shouldBe 7
    liveCounter shouldBe 4
    liveCounter2 shouldBe 5
  }
    .unsafeRunSync()

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

    variable.set(2)

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable.set(2)

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable2.set(10)

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable3.set(5)

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable2.set(100)

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

    variable.set(2)

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable.set(2)

    rx.now() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable2.set(10)

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable3.set(5)

    rx.now() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable2.set(100)

    rx.now() shouldBe "2, 100, 5"
    liveCounter shouldBe 4

  }.unsafeRunSync()

  it should "diamond" in Owned {
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

    variable.set(2)

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "true:false", "false:false") // glitch
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 3

    variable.set(2)

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "true:false", "false:false")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 3

    variable.set(4)

    mapped1 shouldBe List(4, 2, 1)
    mapped2 shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "true:false", "false:false")
    rx.now() shouldBe "true:true"
    liveCounter shouldBe 3

    variable.set(5)

    mapped1 shouldBe List(5, 4, 2, 1)
    mapped2 shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
    received2 shouldBe List(false, true, false)
    receivedRx shouldBe List("false:false", "false:true", "true:true", "true:false", "false:false") // glitch
    rx.now() shouldBe "false:false"
    liveCounter shouldBe 5
  }.unsafeRunSync()

  it should "collect" in Owned {
    val variable        = Var[Option[Int]](Some(1))
    val collected       = variable.collect { case Some(x) => x }(0)
    var collectedStates = Vector.empty[Int]

    collected.foreach(collectedStates :+= _)

    collectedStates shouldBe Vector(1)

    variable.set(None)
    collectedStates shouldBe Vector(1)

    variable.set(Some(17))
    collectedStates shouldBe Vector(1, 17)

  }.unsafeRunSync()

}
