package colibri.reactive

import colibri._
import cats.implicits._
import cats.effect.SyncIO
import monocle.macros.{GenLens, GenPrism}
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class ReactiveSpec extends AsyncFlatSpec with Matchers {

  implicit def unsafeSubscriptionOwner[T]: SubscriptionOwner[SyncIO[T]] = new SubscriptionOwner[SyncIO[T]] {
    def own(owner: SyncIO[T])(subscription: () => Cancelable): SyncIO[T] =
      owner.flatTap(_ => SyncIO(subscription()).void)
  }

  "Rx" should "map with proper subscription lifetime" in Owned(SyncIO {
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
  }).unsafeRunSync()

  it should "nested owners" in Owned(SyncIO {
    var received1 = List.empty[Int]
    var innerRx   = List.empty[Int]
    var outerRx   = List.empty[Int]

    val variable  = Var(1)
    val variable2 = Var(2)

    def test(x: Int)(implicit owner: Owner) = Rx {
      innerRx ::= x
      variable2() * x
    }

    val rx = Rx {
      val curr   = variable()
      outerRx ::= curr
      val result = test(curr)
      result()
    }

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List.empty

    rx.foreach(received1 ::= _)

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List(2)

    variable2.set(3)

    innerRx shouldBe List(1, 1, 1) //TODO: triggering too often
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

    innerRx shouldBe List(3, 3, 3, 2, 1, 1, 1)  //TODO: triggering too often
    outerRx shouldBe List(3, 3, 2, 1, 1)
    received1 shouldBe List(12, 9, 6, 3, 2)
  }).unsafeRunSync()

  it should "nested owners 2" in Owned.function(ownedOwner => SyncIO {
    var received1 = List.empty[Int]
    var innerRx   = List.empty[Int]
    var outerRx   = List.empty[Int]

    val variable  = Var(1)
    val variable2 = Var(2)

    implicit val owner: Owner = ownedOwner

    def test(x: Int)(implicit owner: Owner) = Rx {
      innerRx ::= x
      variable2() * x
    }

    val rx = Rx {
      val curr   = variable()
      outerRx ::= curr
      val result = test(curr)
      result()
    }

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List.empty

    rx.foreach(received1 ::= _)

    innerRx shouldBe List(1)
    outerRx shouldBe List(1)
    received1 shouldBe List(2)

    variable2.set(3)

    innerRx shouldBe List(1, 1, 1) //TODO: triggering too often
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

    innerRx shouldBe List(3, 3, 3, 2, 1, 1, 1)  //TODO: triggering too often
    outerRx shouldBe List(3, 3, 2, 1, 1)
    received1 shouldBe List(12, 9, 6, 3, 2)
  }).unsafeRunSync()

  it should "sequence with nesting" in Owned(SyncIO {
    var received1 = List.empty[Int]
    var mapped   = List.empty[Boolean]

    val variable  = Var[Option[Int]](None)

    val stream = variable.sequence.switchMap { option =>
      mapped ::= option.isDefined

      option match {
        case Some(rx) =>
          val isOdd = rx.map(_ % 2 != 0)
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

    mapped shouldBe List(false)
    received1 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List(false)
    received1 shouldBe List(-1)

    variable.set(Some(1))

    mapped shouldBe List(true, false)
    received1 shouldBe List(2, -1)

    variable.set(Some(2))

    mapped shouldBe List(true, false)
    received1 shouldBe List(1, 12, 2, -1)
  }).unsafeRunSync()

  it should "be distinct" in Owned(SyncIO {
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
  }).unsafeRunSync()

  it should "work without glitches in chain" in Owned(SyncIO {
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
  }).unsafeRunSync()

  it should "work nested" in Owned(SyncIO {
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
  }).unsafeRunSync()

  it should "work with now" in Owned(SyncIO {
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

  }).unsafeRunSync()

  it should "work with multi nesting" in Owned(SyncIO {
    var liveCounter = 0

    val variable  = Var(1)
    val variable2 = Var(2)
    val variable3 = Var(3)

    val rx = Owned(SyncIO {
      Owned(SyncIO {
        Rx {
          liveCounter += 1

          Owned(SyncIO {
            Rx {
              Rx {
                s"${variable()}, ${variable2()}, ${variable3.now()}"
              }
            }(implicitly)()
          }).unsafeRunSync()()
        }
      }).unsafeRunSync()
    }).unsafeRunSync()

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

  }).unsafeRunSync()

  it should "diamond" in Owned(SyncIO {
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
  }).unsafeRunSync()

  it should "collect" in Owned(SyncIO {
    val variable        = Var[Option[Int]](Some(1))
    val collected       = variable.collect { case Some(x) => x }(0)
    var collectedStates = Vector.empty[Int]

    collected.foreach(collectedStates :+= _)

    collectedStates shouldBe Vector(1)

    variable.set(None)
    collectedStates shouldBe Vector(1)

    variable.set(Some(17))
    collectedStates shouldBe Vector(1, 17)

  }).unsafeRunSync()

  it should "collect initial none" in Owned(SyncIO {
    val variable        = Var[Option[Int]](None)
    val collected       = variable.collect { case Some(x) => x }(0)
    var collectedStates = Vector.empty[Int]

    collected.foreach(collectedStates :+= _)

    collectedStates shouldBe Vector(0)

    variable.set(None)
    collectedStates shouldBe Vector(0)

    variable.set(Some(17))
    collectedStates shouldBe Vector(0, 17)

  }).unsafeRunSync()

  it should "sequence on Var[Seq[T]]" in Owned(SyncIO {
    {
      // inner.set on seed value
      val variable                    = Var[Seq[Int]](Seq(1))
      val sequence: Rx[Seq[Var[Int]]] = variable.sequence

      variable.now() shouldBe Seq(1)
      sequence.now().map(_.now()) shouldBe Seq(1)

      sequence.now()(0).set(2)
      variable.now() shouldBe Seq(2)
    }

    {
      // inner.set on value after seed
      val variable                    = Var[Seq[Int]](Seq.empty)
      val sequence: Rx[Seq[Var[Int]]] = variable.sequence

      variable.now() shouldBe Seq.empty
      sequence.now().map(_.now()) shouldBe Seq.empty

      variable.set(Seq(1))
      sequence.now().map(_.now()) shouldBe Seq(1)

      sequence.now()(0).set(2)
      variable.now() shouldBe Seq(2)
    }

    {
      // only trigger outer Rx if collection size changed
      val variable                    = Var[Seq[Int]](Seq(1,2))
      val sequence: Rx[Seq[Var[Int]]] = variable.sequence
      var sequenceTriggered = 0
      sequence.foreach(_ => sequenceTriggered += 1)

      sequenceTriggered shouldBe 1
      sequence.now().size shouldBe 2

      sequence.now()(0).set(3)
      sequence.now().size shouldBe 2
      sequenceTriggered shouldBe 1

      variable.set(Seq(3,4))
      sequence.now().size shouldBe 2
      sequence.now()(0).now() shouldBe 3
      sequence.now()(1).now() shouldBe 4
      sequenceTriggered shouldBe 1

      variable.set(Seq(3,4,5,6))
      sequence.now().size shouldBe 4
      sequence.now()(0).now() shouldBe 3
      sequence.now()(1).now() shouldBe 4
      sequence.now()(2).now() shouldBe 5
      sequenceTriggered shouldBe 2

      variable.set(Seq(7))
      sequence.now().size shouldBe 1
      sequence.now()(0).now() shouldBe 7
      sequenceTriggered shouldBe 3

      variable.set(Seq(8))
      sequence.now().size shouldBe 1
      sequence.now()(0).now() shouldBe 8
      sequenceTriggered shouldBe 3
    }
  }).unsafeRunSync()

  it should "sequence on Var[Option[T]]" in Owned(SyncIO {
    {
      // inner.set on seed value
      val variable                       = Var[Option[Int]](Some(1))
      val sequence: Rx[Option[Var[Int]]] = variable.sequence

      variable.now() shouldBe Some(1)
      sequence.now().map(_.now()) shouldBe Some(1)

      sequence.now().get.set(2)
      variable.now() shouldBe Some(2)
    }

    {
      // inner.set on value after seed
      val variable                       = Var[Option[Int]](Option.empty)
      val sequence: Rx[Option[Var[Int]]] = variable.sequence

      variable.now() shouldBe None
      sequence.now().map(_.now()) shouldBe None

      variable.set(Option(1))
      sequence.now().map(_.now()) shouldBe Option(1)

      sequence.now().get.set(2)
      variable.now() shouldBe Option(2)

      variable.set(None)
      sequence.now().map(_.now()) shouldBe None
    }

    {
      // inner.set on seed value
      val variable                       = Var[Option[Int]](Some(1))
      val sequence: Rx[Option[Var[Int]]] = variable.sequence

      var outerTriggered = 0
      var innerTriggered = 0
      sequence.foreach(_ => outerTriggered += 1)
      sequence.now().foreach(_.foreach(_ => innerTriggered += 1))

      variable.now() shouldBe Some(1)
      sequence.now().map(_.now()) shouldBe Some(1)
      outerTriggered shouldBe 1
      innerTriggered shouldBe 1
      val varRefA = sequence.now().get

      variable.set(Some(2))
      variable.now() shouldBe Some(2)
      sequence.now().map(_.now()) shouldBe Some(2)
      outerTriggered shouldBe 1
      innerTriggered shouldBe 2
      val varRefB = sequence.now().get
      assert(varRefA eq varRefB)
    }
  }).unsafeRunSync()

  it should "lens" in Owned(SyncIO {
    val a: Var[(Int, String)] = Var((0, "Wurst"))
    val b: Var[String]        = a.lens(_._2)((a, b) => a.copy(_2 = b))
    val c: Rx[String]         = b.map(_ + "q")

    a.now() shouldBe ((0, "Wurst"))
    b.now() shouldBe "Wurst"
    c.now() shouldBe "Wurstq"

    a.set((1, "hoho"))
    a.now() shouldBe ((1, "hoho"))
    b.now() shouldBe "hoho"
    c.now() shouldBe "hohoq"

    b.set("Voodoo")
    a.now() shouldBe ((1, "Voodoo"))
    b.now() shouldBe "Voodoo"
    c.now() shouldBe "Voodooq"

    a.set((3, "genau"))
    a.now() shouldBe ((3, "genau"))
    b.now() shouldBe "genau"
    c.now() shouldBe "genauq"

    b.set("Schwein")
    a.now() shouldBe ((3, "Schwein"))
    b.now() shouldBe "Schwein"
    c.now() shouldBe "Schweinq"
  }).unsafeRunSync()

  it should "lens with monocle" in {
    case class Company(name: String, zipcode: Int)
    case class Employee(name: String, company: Company)

    Owned(SyncIO {
      val employee = Var(Employee("jules", Company("wules", 7)))
      val zipcode  = employee.lensO(GenLens[Employee](_.company.zipcode))

      employee.now() shouldBe Employee("jules", Company("wules", 7))
      zipcode.now() shouldBe 7

      zipcode.set(8)
      employee.now() shouldBe Employee("jules", Company("wules", 8))
      zipcode.now() shouldBe 8

      employee.set(Employee("gula", Company("bori", 6)))
      employee.now() shouldBe Employee("gula", Company("bori", 6))
      zipcode.now() shouldBe 6
    }).unsafeRunSync()
  }

  it should "optics operations" in {
    sealed trait Event
    case class EventA(i: Int)    extends Event
    case class EventB(s: String) extends Event

    Owned(SyncIO {
      val eventVar: Var[Event]     = Var[Event](EventA(0))
      val eventNotAVar: Var[Event] = Var[Event](EventB(""))

      val eventAVarOption: Option[Var[EventA]]    = eventVar.prismO(GenPrism[Event, EventA])
      val eventAVarOption2: Option[Var[EventA]]   = eventVar.subType[EventA]
      val eventNotAVarOption: Option[Var[EventA]] = eventNotAVar.prismO(GenPrism[Event, EventA])

      eventAVarOption.isDefined shouldBe true
      eventAVarOption2.isDefined shouldBe true
      eventNotAVarOption.isDefined shouldBe false

      val eventAVar  = eventAVarOption.get
      val eventAVar2 = eventAVarOption2.get

      eventVar.now() shouldBe EventA(0)
      eventAVar.now() shouldBe EventA(0)
      eventAVar2.now() shouldBe EventA(0)

      eventAVar.set(EventA(1))

      eventVar.now() shouldBe EventA(1)
      eventAVar.now() shouldBe EventA(1)
      eventAVar2.now() shouldBe EventA(1)

      eventVar.set(EventB("he"))

      eventVar.now() shouldBe EventB("he")
      eventAVar.now() shouldBe EventA(1)
      eventAVar2.now() shouldBe EventA(1)

      eventAVar.set(EventA(2))

      eventVar.now() shouldBe EventA(2)
      eventAVar.now() shouldBe EventA(2)
      eventAVar2.now() shouldBe EventA(2)

      eventVar.set(EventA(3))

      eventVar.now() shouldBe EventA(3)
      eventAVar.now() shouldBe EventA(3)
      eventAVar2.now() shouldBe EventA(3)
    }).unsafeRunSync()
  }
}
