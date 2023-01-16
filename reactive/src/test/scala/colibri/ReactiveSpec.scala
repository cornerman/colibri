package colibri.reactive

import cats.implicits._
import monocle.macros.{GenLens, GenPrism}
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class ReactiveSpec extends AsyncFlatSpec with Matchers {

  "Rx" should "map with proper subscription lifetime" in {
    var mapped    = List.empty[Int]
    var received1 = List.empty[Int]
    var received2 = List.empty[Int]

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x }

    mapped shouldBe List.empty
    received1 shouldBe List.empty
    received2 shouldBe List.empty

    val cancelR1 = stream.unsafeForeach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(1)
    received2 shouldBe List.empty

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List.empty

    val cancelR2 = stream.unsafeForeach(received2 ::= _)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(2, 1)
    received2 shouldBe List(2)

    variable.set(3)

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 2, 1)
    received2 shouldBe List(3, 2)

    variable.set(4)

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    cancelR1.unsafeCancel()
    cancelR2.unsafeCancel()

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    variable.set(5)

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(4, 3, 2, 1)
    received2 shouldBe List(4, 3, 2)

    val cancelR1b = stream.unsafeForeach(received1 ::= _)
    val cancelR2b = stream.unsafeForeach(received2 ::= _)

    mapped shouldBe List(5, 4, 3, 2, 1)
    received1 shouldBe List(5, 4, 3, 2, 1)
    received2 shouldBe List(5, 4, 3, 2)

    variable.set(6)

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    val cancelX = stream.unsafeSubscribe()

    mapped shouldBe List(6, 5, 4, 3, 2, 1)
    received1 shouldBe List(6, 5, 4, 3, 2, 1)
    received2 shouldBe List(6, 5, 4, 3, 2)

    variable.set(7)

    mapped shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(7, 6, 5, 4, 3, 2)

    variable.set(8)

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    cancelR2b.unsafeCancel()

    mapped shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    variable.set(9)

    mapped shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    cancelR1b.unsafeCancel()

    mapped shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    variable.set(10)

    mapped shouldBe List(10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    cancelX.unsafeCancel()

    mapped shouldBe List(10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)

    variable.set(11)

    mapped shouldBe List(10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
    received1 shouldBe List(9, 8, 7, 6, 5, 4, 3, 2, 1)
    received2 shouldBe List(8, 7, 6, 5, 4, 3, 2)
  }

  it should "nested rx" in {
    var received1 = List.empty[Int]
    var innerRx   = List.empty[Int]
    var outerRx   = List.empty[Int]

    val variable  = Var(1)
    val variable2 = Var(2)

    def test(x: Int) = Rx {
      innerRx ::= x
      variable2() * x
    }

    val rx = Rx {
      val curr   = variable()
      outerRx ::= curr
      val result = test(curr)
      result()
    }.unsafeHot()

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List.empty

    rx.unsafeForeach(received1 ::= _)

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List(2)

    variable2.set(3)

    innerRx shouldBe List(1, 1, 1) // TODO: triggering too often
    outerRx shouldBe List(1, 1)
    received1 shouldBe List(3, 2)

    variable.set(2)

    innerRx shouldBe List(2, 1, 1, 1)
    outerRx shouldBe List(2, 1, 1)
    received1 shouldBe List(6, 3, 2)

    variable.set(3)

    innerRx shouldBe List(3, 2, 1, 1, 1)
    outerRx shouldBe List(3, 2, 1, 1)
    received1 shouldBe List(9, 6, 3, 2)

    variable2.set(4)

    innerRx shouldBe List(3, 3, 3, 2, 1, 1, 1) // TODO: triggering too often
    outerRx shouldBe List(3, 3, 2, 1, 1)
    received1 shouldBe List(12, 9, 6, 3, 2)
  }

  it should "nested rx 2" in {
    var received1 = List.empty[Int]
    var innerRx   = List.empty[Int]
    var outerRx   = List.empty[Int]

    val variable  = Var(1)
    val variable2 = Var(2)

    def test(x: Int) = Rx {
      innerRx ::= x
      variable2() * x
    }

    val rx = Rx {
      val curr   = variable()
      outerRx ::= curr
      val result = test(curr)
      result()
    }

    innerRx shouldBe List.empty
    outerRx shouldBe List.empty
    received1 shouldBe List.empty

    rx.unsafeForeach(received1 ::= _)

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List(2)

    variable2.set(3)

    innerRx shouldBe List(1, 1, 1) // TODO: triggering too often
    outerRx shouldBe List(1, 1)
    received1 shouldBe List(3, 2)

    variable.set(2)

    innerRx shouldBe List(2, 1, 1, 1)
    outerRx shouldBe List(2, 1, 1)
    received1 shouldBe List(6, 3, 2)

    variable.set(3)

    innerRx shouldBe List(3, 2, 1, 1, 1)
    outerRx shouldBe List(3, 2, 1, 1)
    received1 shouldBe List(9, 6, 3, 2)

    variable2.set(4)

    innerRx shouldBe List(3, 3, 3, 2, 1, 1, 1) // TODO: triggering too often
    outerRx shouldBe List(3, 3, 2, 1, 1)
    received1 shouldBe List(12, 9, 6, 3, 2)
  }

  it should "sequence with nesting" in {
    var received1 = List.empty[Int]
    var mapped    = List.empty[Boolean]

    val variable = Var[Option[Int]](None)

    val stream = variable.sequence.switchMap { option =>
      mapped ::= option.isDefined

      option match {
        case Some(rx) =>
          val isOdd  = rx.map(_ % 2 != 0)
          val isEven = rx.map(_ % 2 == 0)

          isEven.switchMap { isEven =>
            isOdd.map { isOdd =>
              if (isEven && !isOdd) 1
              else if (!isEven && isOdd) 2
              else if (isEven && isOdd) 11
              else if (!isEven && !isOdd) 12
              else 0
            }
          }

        case None => Rx.const(-1)
      }
    }

    mapped shouldBe List.empty
    received1 shouldBe List.empty

    stream.unsafeForeach(received1 ::= _)

    mapped shouldBe List(false)
    received1 shouldBe List(-1)

    variable.set(Some(1))

    mapped shouldBe List(true, false)
    received1 shouldBe List(2, -1)

    variable.set(Some(2))

    mapped shouldBe List(true, false)
    received1 shouldBe List(1, 2, -1)
  }

  it should "be distinct" in {
    var mapped    = List.empty[Int]
    var received1 = List.empty[Boolean]

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x % 2 == 0 }

    mapped shouldBe List.empty
    received1 shouldBe List.empty

    stream.unsafeForeach(received1 ::= _)

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
  }

  it should "work without glitches in chain" in {
    var liveCounter = 0
    var mapped      = List.empty[Int]
    var received1   = List.empty[Boolean]
    var receivedRx  = List.empty[String]

    val variable = Var(1)

    val stream = variable.map { x => mapped ::= x; x % 2 == 0 }

    stream.unsafeForeach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(false)

    val rx = Rx {
      liveCounter += 1
      s"${variable()}: ${stream()}"
    }

    rx.unsafeForeach(receivedRx ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(false)
    receivedRx shouldBe List("1: false")
    rx.nowIfSubscribed() shouldBe "1: false"
    liveCounter shouldBe 1

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("2: true", "1: false")
    rx.nowIfSubscribed() shouldBe "2: true"
    liveCounter shouldBe 2

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("2: true", "1: false")
    rx.nowIfSubscribed() shouldBe "2: true"
    liveCounter shouldBe 2

    variable.set(4)

    mapped shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)
    receivedRx shouldBe List("4: true", "2: true", "1: false")
    rx.nowIfSubscribed() shouldBe "4: true"
    liveCounter shouldBe 3

    variable.set(5)

    mapped shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
    receivedRx shouldBe List("5: false", "4: true", "2: true", "1: false")
    rx.nowIfSubscribed() shouldBe "5: false"
    liveCounter shouldBe 4
  }

  it should "work nested" in {
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
    }.unsafeHot()

    rx.nowIfSubscribed() shouldBe 3
    liveCounter shouldBe 1
    liveCounter2 shouldBe 1

    variable.set(2)

    rx.nowIfSubscribed() shouldBe 4
    liveCounter shouldBe 2
    liveCounter2 shouldBe 2

    variable2.set(4)

    rx.nowIfSubscribed() shouldBe 6
    liveCounter shouldBe 3
    liveCounter2 shouldBe 4 // TODO: why do we jump to 4 calculations here instead of 3?

    variable.set(3)

    rx.nowIfSubscribed() shouldBe 7
    liveCounter shouldBe 4
    liveCounter2 shouldBe 5
  }

  it should "work with now" in {
    var liveCounter = 0

    val variable  = Var(1)
    val variable2 = Var(2)
    val variable3 = Var(3)

    val rx = Rx {
      liveCounter += 1
      s"${variable()}, ${variable2()}, ${variable3.now()}"
    }.unsafeHot()

    rx.nowIfSubscribed() shouldBe "1, 2, 3"
    liveCounter shouldBe 1

    variable.set(2)

    rx.nowIfSubscribed() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable.set(2)

    rx.nowIfSubscribed() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable2.set(10)

    rx.nowIfSubscribed() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable3.set(5)

    rx.nowIfSubscribed() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable2.set(100)

    rx.nowIfSubscribed() shouldBe "2, 100, 5"
    liveCounter shouldBe 4

  }

  it should "work with multi nesting" in {
    var liveCounter = 0

    val variable  = Var(1)
    val variable2 = Var(2)
    val variable3 = Var(3)

    val rx = Rx {
      liveCounter += 1
      Rx {
        Rx {
          s"${variable()}, ${variable2()}, ${variable3.now()}"
        }
      }.apply().apply()
    }.unsafeHot()

    rx.nowIfSubscribed() shouldBe "1, 2, 3"
    liveCounter shouldBe 1

    variable.set(2)

    rx.nowIfSubscribed() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable.set(2)

    rx.nowIfSubscribed() shouldBe "2, 2, 3"
    liveCounter shouldBe 2

    variable2.set(10)

    rx.nowIfSubscribed() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable3.set(5)

    rx.nowIfSubscribed() shouldBe "2, 10, 3"
    liveCounter shouldBe 3

    variable2.set(100)

    rx.nowIfSubscribed() shouldBe "2, 100, 5"
    liveCounter shouldBe 4

  }

  it should "diamond" in {
    var liveCounter = 0
    var mapped1     = List.empty[Int]
    var mapped2     = List.empty[Int]
    var received1   = List.empty[Boolean]
    var received2   = List.empty[Boolean]
    var receivedRx  = List.empty[String]

    val variable = Var(1)

    val stream1 = variable.map { x => mapped1 ::= x; x % 2 == 0 }
    val stream2 = variable.map { x => mapped2 ::= x; x % 2 == 0 }

    stream1.unsafeForeach(received1 ::= _)
    stream2.unsafeForeach(received2 ::= _)

    mapped1 shouldBe List(1)
    mapped2 shouldBe List(1)
    received1 shouldBe List(false)
    received2 shouldBe List(false)

    val rx = Rx {
      liveCounter += 1
      s"${stream1()}:${stream2()}"
    }

    rx.unsafeForeach(receivedRx ::= _)

    mapped1 shouldBe List(1)
    mapped2 shouldBe List(1)
    received1 shouldBe List(false)
    received2 shouldBe List(false)
    receivedRx shouldBe List("false:false")
    rx.nowIfSubscribed() shouldBe "false:false"
    liveCounter shouldBe 1

    variable.set(2)

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "true:false", "false:false") // glitch
    rx.nowIfSubscribed() shouldBe "true:true"
    liveCounter shouldBe 3

    variable.set(2)

    mapped1 shouldBe List(2, 1)
    mapped2 shouldBe List(2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "true:false", "false:false")
    rx.nowIfSubscribed() shouldBe "true:true"
    liveCounter shouldBe 3

    variable.set(4)

    mapped1 shouldBe List(4, 2, 1)
    mapped2 shouldBe List(4, 2, 1)
    received1 shouldBe List(true, false)
    received2 shouldBe List(true, false)
    receivedRx shouldBe List("true:true", "true:false", "false:false")
    rx.nowIfSubscribed() shouldBe "true:true"
    liveCounter shouldBe 3

    variable.set(5)

    mapped1 shouldBe List(5, 4, 2, 1)
    mapped2 shouldBe List(5, 4, 2, 1)
    received1 shouldBe List(false, true, false)
    received2 shouldBe List(false, true, false)
    receivedRx shouldBe List("false:false", "false:true", "true:true", "true:false", "false:false") // glitch
    rx.nowIfSubscribed() shouldBe "false:false"
    liveCounter shouldBe 5
  }

  it should "collect initial some" in {
    var collectedStates = Vector.empty[Int]

    val variable        = Var[Option[Int]](Some(1))
    val collected       = variable.collect { case Some(x) => x }.toRx(0)

    collected.unsafeForeach(collectedStates :+= _)

    collected.now() shouldBe 1
    collectedStates shouldBe Vector(1)

    variable.set(None)
    collected.now() shouldBe 1
    collectedStates shouldBe Vector(1)

    variable.set(Some(17))
    collected.now() shouldBe 17
    collectedStates shouldBe Vector(1, 17)
  }

  it should "collect initial none" in {
    var collectedStates = Vector.empty[Int]

    val variable        = Var[Option[Int]](None)
    val collected       = variable.collect { case Some(x) => x }.toRx(0)

    collected.unsafeForeach(collectedStates :+= _)

    collected.now() shouldBe 0
    collectedStates shouldBe Vector(0)

    variable.set(None)
    collected.now() shouldBe 0
    collectedStates shouldBe Vector(0)

    variable.set(Some(17))
    collected.now() shouldBe 17
    collectedStates shouldBe Vector(0, 17)
  }

  it should "collect later initial none" in {
    var tapStates = Vector.empty[Int]
    var collectedStates = Vector.empty[Int]

    val variable        = Var[Option[Int]](None)
    val collected       = variable.collect { case Some(x) => x }.tap(tapStates :+= _)

    val cancelable = collected.unsafeForeach(collectedStates :+= _)

    collectedStates shouldBe Vector.empty
    tapStates shouldBe Vector.empty

    variable.set(Some(0))
    collectedStates shouldBe Vector(0)
    tapStates shouldBe Vector(0)

    collected.toRx.now() shouldBe Some(0)
    collectedStates shouldBe Vector(0)
    tapStates shouldBe Vector(0)

    variable.set(None)
    collectedStates shouldBe Vector(0)
    tapStates shouldBe Vector(0)

    collected.toRx.now() shouldBe Some(0)
    collectedStates shouldBe Vector(0)
    tapStates shouldBe Vector(0)

    variable.set(Some(17))
    collectedStates shouldBe Vector(0, 17)
    tapStates shouldBe Vector(0, 17)

    collected.toRx.now() shouldBe Some(17)
    collectedStates shouldBe Vector(0, 17)
    tapStates shouldBe Vector(0, 17)

    cancelable.unsafeCancel()

    variable.set(Some(18))
    collectedStates shouldBe Vector(0, 17)
    tapStates shouldBe Vector(0, 17)

    collected.toRx.now() shouldBe Some(18)
    collectedStates shouldBe Vector(0, 17)
    tapStates shouldBe Vector(0, 17, 18)
  }

  it should "sequence on Var[Seq[T]]" in {
    {
      // inner.set on seed value
      val variable                    = Var[Seq[Int]](Seq(1))
      val sequence: Rx[Seq[Var[Int]]] = variable.sequence.unsafeHot()

      variable.nowIfSubscribed() shouldBe Seq(1)
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Seq(1)

      sequence.nowIfSubscribed().apply(0).set(2)
      variable.nowIfSubscribed() shouldBe Seq(2)
    }

    {
      // inner.set on value after seed
      val variable                    = Var[Seq[Int]](Seq.empty)
      val sequence: Rx[Seq[Var[Int]]] = variable.sequence.unsafeHot()

      variable.nowIfSubscribed() shouldBe Seq.empty
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Seq.empty

      variable.set(Seq(1))
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Seq(1)

      sequence.nowIfSubscribed().apply(0).set(2)
      variable.nowIfSubscribed() shouldBe Seq(2)
    }
  }

  it should "sequence on Var[Option[T]]" in {
    {
      // inner.set on seed value
      val variable                       = Var[Option[Int]](Some(1))
      val sequence: Rx[Option[Var[Int]]] = variable.sequence.unsafeHot()

      variable.nowIfSubscribed() shouldBe Some(1)
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Some(1)

      sequence.nowIfSubscribed().get.set(2)
      variable.nowIfSubscribed() shouldBe Some(2)
    }

    {
      // inner.set on value after seed
      val variable                       = Var[Option[Int]](Option.empty)
      val sequence: Rx[Option[Var[Int]]] = variable.sequence.unsafeHot()

      variable.nowIfSubscribed() shouldBe None
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe None

      variable.set(Option(1))
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Option(1)

      sequence.nowIfSubscribed().get.set(2)
      variable.nowIfSubscribed() shouldBe Option(2)

      variable.set(None)
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe None
    }

    {
      // inner.set on seed value
      val variable                       = Var[Option[Int]](Some(1))
      val sequence: Rx[Option[Var[Int]]] = variable.sequence.unsafeHot()

      var outerTriggered = 0
      var innerTriggered = 0
      sequence.unsafeForeach(_ => outerTriggered += 1)
      sequence.nowIfSubscribed().foreach(_.unsafeForeach(_ => innerTriggered += 1))

      variable.nowIfSubscribed() shouldBe Some(1)
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Some(1)
      outerTriggered shouldBe 1
      innerTriggered shouldBe 1
      val varRefA = sequence.nowIfSubscribed().get

      variable.set(Some(2))
      variable.nowIfSubscribed() shouldBe Some(2)
      sequence.nowIfSubscribed().map(_.nowIfSubscribed()) shouldBe Some(2)
      outerTriggered shouldBe 1
      innerTriggered shouldBe 2
      val varRefB = sequence.nowIfSubscribed().get
      assert(varRefA eq varRefB)
    }
  }

  it should "transform and keep state" in {
    val original: Var[Int] = Var(1)
    val encoded: Var[String] = original.transformVar[String](
      _.contramapIterable(str => str.toIntOption)
    )(
      _.map(num => num.toString)
    )

    encoded.unsafeSubscribe()

    original.nowIfSubscribed() shouldBe 1
    encoded.nowIfSubscribed() shouldBe "1"

    original.set(2)

    original.nowIfSubscribed() shouldBe 2
    encoded.nowIfSubscribed() shouldBe "2"

    encoded.set("3")

    original.nowIfSubscribed() shouldBe 3
    encoded.nowIfSubscribed() shouldBe "3"

    encoded.set("nope")

    original.nowIfSubscribed() shouldBe 3
    encoded.nowIfSubscribed() shouldBe "nope"
  }

  it should "lens" in {
    val a: Var[(Int, String)] = Var((0, "Wurst"))
    val b: Var[String]        = a.lens(_._2)((a, b) => a.copy(_2 = b))
    val c: Rx[String]         = b.map(_ + "q")

    a.unsafeSubscribe()
    b.unsafeSubscribe()
    c.unsafeSubscribe()

    a.nowIfSubscribed() shouldBe ((0, "Wurst"))
    b.nowIfSubscribed() shouldBe "Wurst"
    c.nowIfSubscribed() shouldBe "Wurstq"

    a.set((1, "hoho"))
    a.nowIfSubscribed() shouldBe ((1, "hoho"))
    b.nowIfSubscribed() shouldBe "hoho"
    c.nowIfSubscribed() shouldBe "hohoq"

    b.set("Voodoo")
    a.nowIfSubscribed() shouldBe ((1, "Voodoo"))
    b.nowIfSubscribed() shouldBe "Voodoo"
    c.nowIfSubscribed() shouldBe "Voodooq"

    a.set((3, "genau"))
    a.nowIfSubscribed() shouldBe ((3, "genau"))
    b.nowIfSubscribed() shouldBe "genau"
    c.nowIfSubscribed() shouldBe "genauq"

    b.set("Schwein")
    a.nowIfSubscribed() shouldBe ((3, "Schwein"))
    b.nowIfSubscribed() shouldBe "Schwein"
    c.nowIfSubscribed() shouldBe "Schweinq"
  }

  it should "lens with monocle" in {
    case class Company(name: String, zipcode: Int)
    case class Employee(name: String, company: Company)

    val employee = Var(Employee("jules", Company("wules", 7)))
    val zipcode  = employee.lensO(GenLens[Employee](_.company.zipcode))

    zipcode.unsafeSubscribe()

    employee.nowIfSubscribed() shouldBe Employee("jules", Company("wules", 7))
    zipcode.nowIfSubscribed() shouldBe 7

    zipcode.set(8)
    employee.nowIfSubscribed() shouldBe Employee("jules", Company("wules", 8))
    zipcode.nowIfSubscribed() shouldBe 8

    employee.set(Employee("gula", Company("bori", 6)))
    employee.nowIfSubscribed() shouldBe Employee("gula", Company("bori", 6))
    zipcode.nowIfSubscribed() shouldBe 6
  }

  it should "optics operations" in {
    sealed trait Event
    case class EventA(i: Int)    extends Event
    case class EventB(s: String) extends Event

    val eventVar: Var[Event]    = Var[Event](EventA(0))
    val eventNotVar: Var[Event] = Var[Event](EventB(""))

    val eventAVar    = eventVar.prismO(GenPrism[Event, EventA])(null)
    val eventAVar2   = eventVar.subType[EventA](null)
    val eventNotAVar = eventNotVar.prismO(GenPrism[Event, EventA])(null)

    eventAVar.unsafeSubscribe()
    eventAVar2.unsafeSubscribe()
    eventNotAVar.unsafeSubscribe()

    eventVar.nowIfSubscribed() shouldBe EventA(0)
    eventAVar.nowIfSubscribed() shouldBe EventA(0)
    eventAVar2.nowIfSubscribed() shouldBe EventA(0)
    eventNotAVar.nowIfSubscribed() shouldBe null

    eventAVar.set(EventA(1))

    eventVar.nowIfSubscribed() shouldBe EventA(1)
    eventAVar.nowIfSubscribed() shouldBe EventA(1)
    eventAVar2.nowIfSubscribed() shouldBe EventA(1)

    eventVar.set(EventB("he"))

    eventVar.nowIfSubscribed() shouldBe EventB("he")
    eventAVar.nowIfSubscribed() shouldBe EventA(1)
    eventAVar2.nowIfSubscribed() shouldBe EventA(1)

    eventAVar.set(EventA(2))

    eventVar.nowIfSubscribed() shouldBe EventA(2)
    eventAVar.nowIfSubscribed() shouldBe EventA(2)
    eventAVar2.nowIfSubscribed() shouldBe EventA(2)

    eventVar.set(EventA(3))

    eventVar.nowIfSubscribed() shouldBe EventA(3)
    eventAVar.nowIfSubscribed() shouldBe EventA(3)
    eventAVar2.nowIfSubscribed() shouldBe EventA(3)
  }

  it should "map and now()" in {
    val variable = Var(1)
    val mapped   = variable.map(_ + 1)

    variable.nowIfSubscribedOption() shouldBe Some(1)
    variable.now() shouldBe 1
    variable.nowIfSubscribedOption() shouldBe Some(1)
    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 2
    mapped.nowIfSubscribedOption() shouldBe None

    variable.set(2)

    variable.nowIfSubscribedOption() shouldBe Some(2)
    variable.now() shouldBe 2
    variable.nowIfSubscribedOption() shouldBe Some(2)
    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 3
    mapped.nowIfSubscribedOption() shouldBe None
  }

  it should "drop" in {
    var triggers1       = List.empty[Int]
    val variable1       = Var(1)
    val variable1Logged = variable1.drop(1).tap(triggers1 ::= _).toRx

    triggers1 shouldBe List.empty
    variable1Logged.nowIfSubscribedOption() shouldBe None
    triggers1 shouldBe List.empty

    val cancelable = variable1Logged.unsafeSubscribe()

    triggers1 shouldBe List.empty
    variable1Logged.nowIfSubscribedOption() shouldBe Some(None)
    triggers1 shouldBe List.empty

    variable1.set(2)

    triggers1 shouldBe List(2)
    variable1Logged.nowIfSubscribedOption() shouldBe Some(Some(2))
    triggers1 shouldBe List(2)

    variable1.set(3)

    triggers1 shouldBe List(3, 2)
    variable1Logged.nowIfSubscribedOption() shouldBe Some(Some(3))
    triggers1 shouldBe List(3, 2)

    cancelable.unsafeCancel()

    variable1.set(4)

    triggers1 shouldBe List(3, 2)
    variable1Logged.nowIfSubscribedOption() shouldBe None
    triggers1 shouldBe List(3, 2)

    variable1Logged.now() shouldBe None
    triggers1 shouldBe List(3, 2)

    variable1.set(5)

    triggers1 shouldBe List(3, 2)
    variable1Logged.nowIfSubscribedOption() shouldBe None
    triggers1 shouldBe List(3, 2)

    variable1Logged.now() shouldBe None
    triggers1 shouldBe List(3, 2)
  }

  it should "subscribe and now on rx with lazy subscriptions" in {
    var triggers1      = List.empty[Int]
    var triggerRxCount = 0

    val variable1       = Var(1)
    val variable1Logged = variable1.tap(triggers1 ::= _)

    val mapped = Rx {
      triggerRxCount += 1
      variable1Logged() + 1
    }

    val cancelable = mapped.unsafeSubscribe()

    triggers1 shouldBe List(1)
    triggerRxCount shouldBe 1

    mapped.nowIfSubscribedOption() shouldBe Some(2)
    mapped.now() shouldBe 2
    mapped.nowIfSubscribedOption() shouldBe Some(2)

    variable1.set(2)

    triggers1 shouldBe List(2, 1)
    triggerRxCount shouldBe 2

    mapped.nowIfSubscribedOption() shouldBe Some(3)
    mapped.now() shouldBe 3
    mapped.nowIfSubscribedOption() shouldBe Some(3)

    cancelable.unsafeCancel()

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 3
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List(2, 2, 1)
    triggerRxCount shouldBe 3
  }

  it should "combine rx and rxlater in Rx" in {
    var triggers1      = List.empty[Int]
    var triggers2      = List.empty[Int]
    var triggerRxCount = 0

    val variable1       = VarLater[Int]()
    val variable2       = Var(1)
    val variable1Logged = variable1.tap(triggers1 ::= _)
    val variable2Logged = variable2.tap(triggers2 ::= _)

    val mapped = Rx {
      triggerRxCount += 1
      variable1Logged.toRx().getOrElse(0) + variable2Logged()
    }

    triggers1 shouldBe List.empty
    triggers2 shouldBe List.empty
    triggerRxCount shouldBe 0

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 1
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List.empty
    triggers2 shouldBe List(1)
    triggerRxCount shouldBe 1

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 1
    mapped.nowIfSubscribedOption() shouldBe None

    variable1.set(10)

    triggers1 shouldBe List.empty
    triggers2 shouldBe List(1, 1)
    triggerRxCount shouldBe 2

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 11
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List(10)
    triggers2 shouldBe List(1, 1, 1)
    triggerRxCount shouldBe 3

    val cancelable = mapped.unsafeSubscribe()

    triggers1 shouldBe List(10, 10)
    triggers2 shouldBe List(1, 1, 1, 1)
    triggerRxCount shouldBe 4

    mapped.nowIfSubscribedOption() shouldBe Some(11)
    mapped.now() shouldBe 11
    mapped.nowIfSubscribedOption() shouldBe Some(11)

    variable1.set(20)

    triggers1 shouldBe List(20, 10, 10)
    triggers2 shouldBe List(1, 1, 1, 1)
    triggerRxCount shouldBe 5

    mapped.nowIfSubscribedOption() shouldBe Some(21)
    mapped.now() shouldBe 21
    mapped.nowIfSubscribedOption() shouldBe Some(21)

    variable2.set(2)

    triggers1 shouldBe List(20, 10, 10)
    triggers2 shouldBe List(2, 1, 1, 1, 1)
    triggerRxCount shouldBe 6

    mapped.nowIfSubscribedOption() shouldBe Some(22)
    mapped.now() shouldBe 22
    mapped.nowIfSubscribedOption() shouldBe Some(22)

    variable2.set(3)

    mapped.nowIfSubscribedOption() shouldBe Some(23)
    mapped.now() shouldBe 23
    mapped.nowIfSubscribedOption() shouldBe Some(23)

    triggers1 shouldBe List(20, 10, 10)
    triggers2 shouldBe List(3, 2, 1, 1, 1, 1)
    triggerRxCount shouldBe 7

    variable1.set(30)

    mapped.nowIfSubscribedOption() shouldBe Some(33)
    mapped.now() shouldBe 33
    mapped.nowIfSubscribedOption() shouldBe Some(33)

    triggers1 shouldBe List(30, 20, 10, 10)
    triggers2 shouldBe List(3, 2, 1, 1, 1, 1)
    triggerRxCount shouldBe 8

    cancelable.unsafeCancel()

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 33
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List(30, 30, 20, 10, 10)
    triggers2 shouldBe List(3, 3, 2, 1, 1, 1, 1)
    triggerRxCount shouldBe 9
  }

  it should "now() in Rx, and owners with lazy subscriptions" in {
    var triggers1      = List.empty[Int]
    var triggers2      = List.empty[Int]
    var triggerRxCount = 0

    val variable1       = Var(1)
    val variable2       = Var(1)
    val variable1Logged = variable1.tap(triggers1 ::= _)
    val variable2Logged = variable2.tap(triggers2 ::= _)

    // use mock locally
    implicitly[NowOwner].isInstanceOf[LiveOwner] shouldBe false
    val mapped = Rx {
      // use mock locally
      implicitly[NowOwner].isInstanceOf[LiveOwner] shouldBe true

      triggerRxCount += 1
      variable1Logged() + variable2Logged.now()
    }

    triggers1 shouldBe List.empty
    triggers2 shouldBe List.empty
    triggerRxCount shouldBe 0

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 2
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List(1)
    triggers2 shouldBe List(1)
    triggerRxCount shouldBe 1

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 2
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List(1, 1)
    triggers2 shouldBe List(1, 1)
    triggerRxCount shouldBe 2

    val cancelable = mapped.unsafeSubscribe()

    triggers1 shouldBe List(1, 1, 1)
    triggers2 shouldBe List(1, 1, 1)
    triggerRxCount shouldBe 3

    mapped.nowIfSubscribedOption() shouldBe Some(2)
    mapped.now() shouldBe 2
    mapped.nowIfSubscribedOption() shouldBe Some(2)

    variable1.set(2)

    triggers1 shouldBe List(2, 1, 1, 1)
    triggers2 shouldBe List(1, 1, 1)
    triggerRxCount shouldBe 4

    mapped.nowIfSubscribedOption() shouldBe Some(3)
    mapped.now() shouldBe 3
    mapped.nowIfSubscribedOption() shouldBe Some(3)

    triggers1 shouldBe List(2, 1, 1, 1)
    triggers2 shouldBe List(1, 1, 1)
    triggerRxCount shouldBe 4

    variable2.set(10)

    mapped.nowIfSubscribedOption() shouldBe Some(3)
    mapped.now() shouldBe 3
    mapped.nowIfSubscribedOption() shouldBe Some(3)

    triggers1 shouldBe List(2, 1, 1, 1)
    triggers2 shouldBe List(10, 1, 1, 1)
    triggerRxCount shouldBe 4

    variable1.set(3)

    mapped.nowIfSubscribedOption() shouldBe Some(13)
    mapped.now() shouldBe 13
    mapped.nowIfSubscribedOption() shouldBe Some(13)

    triggers1 shouldBe List(3, 2, 1, 1, 1)
    triggers2 shouldBe List(10, 1, 1, 1)
    triggerRxCount shouldBe 5

    cancelable.unsafeCancel()

    mapped.nowIfSubscribedOption() shouldBe None
    mapped.now() shouldBe 13
    mapped.nowIfSubscribedOption() shouldBe None

    triggers1 shouldBe List(3, 3, 2, 1, 1, 1)
    triggers2 shouldBe List(10, 10, 1, 1, 1)
    triggerRxCount shouldBe 6
  }

  it should "start and stop" in {
    var triggers1 = List.empty[Int]
    var results1  = List.empty[Int]

    val variable1 = Var(1)

    val cancelable1 = variable1.tap(triggers1 ::= _).unsafeForeach(results1 ::= _)

    triggers1 shouldBe List(1)
    results1 shouldBe List(1)

    variable1.set(2)

    triggers1 shouldBe List(2, 1)
    results1 shouldBe List(2, 1)

    cancelable1.unsafeCancel()
    variable1.set(3)

    triggers1 shouldBe List(2, 1)
    results1 shouldBe List(2, 1)

    val cancelable1b = variable1.tap(triggers1 ::= _).unsafeForeach(results1 ::= _)

    triggers1 shouldBe List(3, 2, 1)
    results1 shouldBe List(3, 2, 1)

    variable1.set(4)

    triggers1 shouldBe List(4, 3, 2, 1)
    results1 shouldBe List(4, 3, 2, 1)

    cancelable1b.unsafeCancel()
    variable1.set(5)

    triggers1 shouldBe List(4, 3, 2, 1)
    results1 shouldBe List(4, 3, 2, 1)
  }

  "RxEvent" should "combine, start and stop" in {
    var triggers1      = List.empty[Int]
    var triggers2      = List.empty[Int]
    var triggerRxCount = 0
    var results1       = List.empty[Int]
    var results2       = List.empty[Int]

    val variable1       = VarEvent[Int]()
    val rx2             = RxEvent(1, 2)
    val variable1Logged = variable1.tap(triggers1 ::= _)
    val rx2Logged       = rx2.tap(triggers2 ::= _)

    // use mock locally
    val mapped = variable1Logged.combineLatestMap(rx2Logged) { (a, b) =>
      triggerRxCount += 1
      a + b
    }

    triggers1 shouldBe List.empty
    triggers2 shouldBe List.empty
    triggerRxCount shouldBe 0
    results1 shouldBe List.empty
    results2 shouldBe List.empty

    val cancelable1 = mapped.unsafeForeach(results1 ::= _)

    triggers1 shouldBe List.empty
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 0
    results1 shouldBe List.empty
    results2 shouldBe List.empty

    variable1.set(100)

    triggers1 shouldBe List(100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 1
    results1 shouldBe List(102)
    results2 shouldBe List.empty

    variable1.set(200)

    triggers1 shouldBe List(200, 100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 2
    results1 shouldBe List(202, 102)
    results2 shouldBe List.empty

    val cancelable2 = mapped.unsafeForeach(results2 ::= _)

    triggers1 shouldBe List(200, 100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 2
    results1 shouldBe List(202, 102)
    results2 shouldBe List.empty

    variable1.set(300)

    triggers1 shouldBe List(300, 200, 100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 3
    results1 shouldBe List(302, 202, 102)
    results2 shouldBe List(302)

    cancelable1.unsafeCancel()

    triggers1 shouldBe List(300, 200, 100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 3
    results1 shouldBe List(302, 202, 102)
    results2 shouldBe List(302)

    variable1.set(400)

    triggers1 shouldBe List(400, 300, 200, 100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 4
    results1 shouldBe List(302, 202, 102)
    results2 shouldBe List(402, 302)

    cancelable2.unsafeCancel()
    variable1.set(500)

    triggers1 shouldBe List(400, 300, 200, 100)
    triggers2 shouldBe List(2, 1)
    triggerRxCount shouldBe 4
    results1 shouldBe List(302, 202, 102)
    results2 shouldBe List(402, 302)

    val cancelable1b = mapped.unsafeForeach(results1 ::= _)

    triggers1 shouldBe List(400, 300, 200, 100)
    triggers2 shouldBe List(2, 1, 2, 1)
    triggerRxCount shouldBe 4
    results1 shouldBe List(302, 202, 102)
    results2 shouldBe List(402, 302)

    variable1.set(1000)

    triggers1 shouldBe List(1000, 400, 300, 200, 100)
    triggers2 shouldBe List(2, 1, 2, 1)
    triggerRxCount shouldBe 5
    results1 shouldBe List(1002, 302, 202, 102)
    results2 shouldBe List(402, 302)

    cancelable1b.unsafeCancel()
    variable1.set(2000)

    triggers1 shouldBe List(1000, 400, 300, 200, 100)
    triggers2 shouldBe List(2, 1, 2, 1)
    triggerRxCount shouldBe 5
    results1 shouldBe List(1002, 302, 202, 102)
    results2 shouldBe List(402, 302)
  }
}
