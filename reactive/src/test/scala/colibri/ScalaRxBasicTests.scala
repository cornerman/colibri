package colibri.reactive

import org.scalatest.Checkpoints
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

import scala.collection.mutable

@annotation.nowarn
class ScalaRxBasicTests extends AsyncFlatSpec with Matchers {

  // We dont care about potential Rx leaks in BasicTest
  implicit val owner: Owner = Owner.unsafeGlobal

  "sigTests basic" should "rxHelloWorld" in {
    val a = Var(1); val b = Var(2)
    val c = Rx.function { owner => a()(owner) + b()(owner) }
    assert(c.now() == 3)
    a() = 4
    assert(c.now() == 6)
  }
  it should "ordering" in {
    var changes = ""
    val a       = Var(1)
    val b       = Rx { changes += "b"; a() + 1 }
    val c       = Rx { changes += "c"; a() + b() }
    assert(changes == "bc")
    a() = 4
    assert(changes == "bcbc")
  }
  it should "options" in {
    val a = Var[Option[Int]](None)
    val b = Var[Option[Int]](None)
    val c = Rx {
      a().flatMap { x =>
        b().map { y =>
          x + y
        }
      }
    }
    a() = Some(1)
    b() = Some(2)
    assert(c.now() == Some(3))
  }
  it should "rxcreate" in {
    val trigger = Var(5)
    val a       = Rx.create[Int](0) { write =>
      trigger.foreachLater { _ =>
        write() = trigger.now()
      }
    }

    assert(a.now() == 0)

    trigger() = 7
    assert(a.now() == 7)
  }
  "sigTests languageFeatures" should "patternMatching" in {
    val a = Var(1); val b = Var(2)
    val c = Rx {
      a() match {
        case 0 => b()
        case x => x
      }
    }
    assert(c.now() == 1)
    a() = 0
    assert(c.now() == 2)
  }
  it should "implicitConversions" in {
    val a = Var(1); val b = Var(2)
    val c = Rx {
      a() to b()
    }
    assert(c.now() == (1 to 2))
    a() = 0
    assert(c.now() == (0 to 2))
  }
  it should "useInByNameParameters" in {
    val a = Var(1)

    val b = Rx { Some(1).getOrElse(a()) }
    assert(b.now() == 1)
  }

  "obsTests" should "helloWorld" in {
    val a     = Var(1)
    var count = 0
    a.foreach { _ =>
      count = a.now() + 1
    }
    assert(count == 2)
    a() = 4
    assert(count == 5)
  }
  it should "helloWorld2" in {
    val a     = Var(1)
    var count = 0
    a.foreach { a =>
      count = a + 1
    }
    assert(count == 2)
    a() = 4
    assert(count == 5)
  }
  it should "skipInitial" in {
    val a     = Var(1)
    var count = 0
    a.foreachLater { _ =>
      count = count + 1
    }
    assert(count == 0)
    a() = 2
    assert(count == 1)
    a() = 2
    assert(count == 1)
    a() = 3
    assert(count == 2)
  }
  it should "skipInitial2" in {
    val a     = Var(1)
    var count = 0
    a.foreachLater { a =>
      count = a + 1
    }
    assert(count == 0)
    a() = 2
    assert(count == 3)
    a() = 3
    assert(count == 4)
  }

  it should "simpleExample" in {
    val a         = Var(1)
    val b         = Rx { println("CALC B"); a() * 2 }
    val c         = Rx { println("CALC C"); a() + 1 }
    val d         = Rx { println("CALC D"); b() + c() }
    val bReceived = mutable.ArrayBuffer.empty[Int]
    val cReceived = mutable.ArrayBuffer.empty[Int]
    val dReceived = mutable.ArrayBuffer.empty[Int]
    b.foreach { bReceived += _ }
    c.foreach { cReceived += _ }
    d.foreach { dReceived += _ }

    assert(bReceived.toList == List(2))
    assert(cReceived.toList == List(2))
    assert(dReceived.toList == List(4))

    println("GO")
    a() = 2
    assert(bReceived.toList == List(2, 4))
    assert(cReceived.toList == List(2, 3))
    assert(dReceived.toList == List(4, 7))

    a() = 1
    assert(bReceived.toList == List(2, 4, 2))
    assert(cReceived.toList == List(2, 3, 2))
    assert(dReceived.toList == List(4, 7, 4))
  }
  it should "killing" in {
    implicit val owner: Owner = Owner.unsafeRef()
    val sub                   = owner.unsafeSubscribe()

    val a = Var(1)
    var i = 0
    a.foreach(_ => i += 1)
    assert(
      // a.observers.size == 1,
      i == 1,
    )
    a() = 2
    assert(i == 2)
    sub.unsafeCancel()
    a() = 3
    assert(
      // a.observers.size == 0,
      i == 2,
    )
  }
  it should "killingInsideRx" in {
    implicit val owner: Owner = Owner.unsafeRef()
    val sub                   = owner.unsafeSubscribe()

    val a = Var(1)
    var i = 0

    val cp = new Checkpoints.Checkpoint
    Rx.function { implicit owner =>
      a.foreach(_ => i += 1)
      cp(
        assert(
          // a.observers.size == 1,
          i == 1,
        ),
      )
      a() = 2
      cp(assert(i == 2))
      sub.unsafeCancel()
      a() = 3
      cp(
        assert(
          // a.observers.size == 0,
          i == 2,
        ),
      )
    }

    cp.reportAll()
    succeed
  }
  it should "killingInsideRx cancelable" in {
    implicit val owner: Owner = Owner.unsafeRef()
    val sub                   = owner.unsafeSubscribe()

    val a = Var(1)
    var i = 0

    val cp = new Checkpoints.Checkpoint
    Rx.function { implicit owner =>
      a.foreach(_ => i += 1)
      cp(
        assert(
          // a.observers.size == 1,
          i == 1,
        ),
      )
      a() = 2
      cp(assert(i == 2))
      owner.cancelable.unsafeCancel()
      a() = 3
      cp(
        assert(
          // a.observers.size == 0,
          i == 2,
        ),
      )
    }

    cp.reportAll()
    succeed
  }
  it should "killingVar" in {
    implicit val owner: Owner = Owner.unsafeRef()
    val sub                   = owner.unsafeSubscribe()

    val a = Var(1)
    var i = 0
    a.foreach(_ => i += 1)
    assert(
      // a.observers.size == 1,
      i == 1,
    )
    a() = 2
    assert(i == 2)
    sub.unsafeCancel()
    a() = 3
    assert(
      // a.observers.size == 0,
      i == 2,
    )
  }

  // "errorHandling" should "simpleCatch" in {
  //   val a = Var(1L)
  //   val b = Rx { 1 / a() }
  //   assert(b.now() == 1)
  //   assert(b.toTry == Success(1L))
  //   a() = 0
  //   intercept[Exception] {
  //     b.now()
  //     ()
  //   }
  //   assertMatch(b.toTry) { case Failure(_) => }
  // }
  // it should "longChain" in {
  //   val a = Var(1L)
  //   val b = Var(2L)

  //   val c = Rx { a() / b() }
  //   val d = Rx { a() * 5 }
  //   val e = Rx { 5 / b() }
  //   val f = Rx { a() + b() + 2 }
  //   val g = Rx { f() + c() }

  //   assertMatch(c.toTry) { case Success(0) => }
  //   assertMatch(d.toTry) { case Success(5) => }
  //   assertMatch(e.toTry) { case Success(2) => }
  //   assertMatch(f.toTry) { case Success(5) => }
  //   assertMatch(g.toTry) { case Success(5) => }

  //   b() = 0

  //   assertMatch(c.toTry) { case Failure(_) => }
  //   assertMatch(d.toTry) { case Success(5) => }
  //   assertMatch(e.toTry) { case Failure(_) => }
  //   assertMatch(f.toTry) { case Success(3) => }
  //   assertMatch(g.toTry) { case Failure(_) => }
  // }
  it should "dontPropagateIfUnchanged" in {
    val a     = Var(1)
    var ai    = 0
    var thing = 0
    val b     = Rx { math.max(a(), 0) + thing }
    var bi    = 0
    val c     = Rx { b() / 2 }
    var ci    = 0

    a.foreach { _ => ai += 1 }
    b.foreach { _ => bi += 1 }
    c.foreach { _ => ci += 1 }
    // if c doesn't change (because of rounding) don't update ci
    assert(ai == 1); assert(bi == 1); assert(ci == 1)
    a() = 0
    assert(ai == 2); assert(bi == 2); assert(ci == 1)
    a() = 1
    assert(ai == 3); assert(bi == 3); assert(ci == 1)
    // but if c changes then update ci
    a() = 2
    assert(ai == 4); assert(bi == 4); assert(ci == 2)

    // if a doesn't change, don't update anything
    a() = 2
    assert(ai == 4); assert(bi == 4); assert(ci == 2)

    // if b doesn't change, don't update bi or ci
    a() = 0
    assert(ai == 5); assert(bi == 5); assert(ci == 3)
    a() = -1
    assert(ai == 6); assert(bi == 5); assert(ci == 3)

    // all change then all update
    a() = 124
    assert(ai == 7); assert(bi == 6); assert(ci == 4)

    // recalcing with no change means no update
    // b.recalc()
    // assert(ai == 7); assert(bi == 6); assert(ci == 4)

    // recalcing with change (for whatever reason) updates downstream
    // thing = 12
    // b.recalc()
    // assert(ai == 7); assert(bi == 7); assert(ci == 5)
  }
  it should "printing" in {
    val v = Var(1).toString
    val r = Rx(1).toString
    assert(v.startsWith("Var@"))
    assert(v.endsWith("(1)"))
    assert(r.startsWith("Rx@"))
    assert(r.endsWith("(1)"))
  }
}
