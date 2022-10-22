package colibri.reactive

import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

@annotation.nowarn
class ScalaRxAdvancedTests extends AsyncFlatSpec with Matchers {

  "nesting" should "nestedRxs" in {
    import Owner.Unsafe._

    val a = Var(1)
    val b = Rx {
      Rx { a() } -> Rx { math.random }
    }
    val r = b.now()._2.now()
    a() = 2
    assert(b.now()._2.now() == r)
  }

  // "recalc" - {
  //   import Owner.Unsafe.__

  //   var source = 0
  //   val a = Rx{
  //     source
  //   }
  //   var i = 0
  //   val o = a.foreach{
  //     i += 1
  //   }
  //   assert(i == 1)
  //   assert(a.now() == 0)
  //   source = 1
  //   assert(a.now() == 0)
  //   a.recalc()
  //   assert(a.now() == 1)
  //   assert(i == 2)
  // }
  it should "multiset" in {
    import Owner.Unsafe._

    val a = Var(1)
    val b = Var(1)
    val c = Var(1)
    val d = Rx {
      a() + b() + c()
    }
    var i = 0
    val o = d.foreach { _ =>
      i += 1
    }
    assert(i == 1)
    assert(d.now() == 3)
    a() = 2
    assert(i == 2)
    assert(d.now() == 4)
    b() = 2
    assert(i == 3)
    assert(d.now() == 5)
    c() = 2
    assert(i == 4)
    assert(d.now() == 6)

    RxWriter.update(
      a -> 3,
      b -> 3,
      c -> 3,
    )

    assert(i == 5)
    assert(d.now() == 9)

    RxWriter.update(
      Seq(
        a -> 4,
        b -> 5,
        c -> 6,
      ): _*,
    )

    assert(i == 6)
    assert(d.now() == 15)
  }
  it should "webPage" in {
    import Owner.Unsafe._

    var fakeTime = 123
    trait WebPage {
      def fTime          = fakeTime
      val time           = Var(fTime)
      def update(): Unit = time() = fTime
      val html: Rx[String]
    }
    class HomePage extends WebPage {
      val html = Rx { "Home Page! time: " + time() }
    }
    class AboutPage extends WebPage {
      val html = Rx { "About Me, time: " + time() }
    }

    val url  = Var("www.mysite.com/home")
    val page = Rx {
      url() match {
        case "www.mysite.com/home"  => new HomePage()
        case "www.mysite.com/about" => new AboutPage()
      }
    }
    assert(page.now().html.now() == "Home Page! time: 123")

    fakeTime = 234
    page.now().update()
    assert(page.now().html.now() == "Home Page! time: 234")

    fakeTime = 345
    url() = "www.mysite.com/about"
    assert(page.now().html.now() == "About Me, time: 345")

    fakeTime = 456
    page.now().update()
    assert(page.now().html.now() == "About Me, time: 456")
  }
  it should "higherOrderRxs" in {
    import Owner.Unsafe._

    val a = Var(1)
    val b = Var(2)
    val c = Rx(Rx(a() + b()) -> (a() - b()))

    // assert(a.downStream.size == 2)
    // assert(b.downStream.size == 2)
    assert(c.now()._1.now() == 3)
    assert(c.now()._2 == -1)

    a() = 2

    // assert(a.downStream.size == 2)
    // assert(b.downStream.size == 2)
    assert(c.now()._1.now() == 4)
    assert(c.now()._2 == 0)

    b() = 3

    // assert(a.downStream.size == 2)
    // assert(b.downStream.size == 2)
    assert(c.now()._1.now() == 5)
    assert(c.now()._2 == -1)
  }

  // it should "leakyRx" in {
  //   var testY = 0
  //   var testZ = 0
  //   val a     = Var(10)

// //      Correct way to implement a def: Rx[_]
  //   def y()(implicit zzz: Ctx.Owner) = Rx { testY += 1; a() }

  //   // This way will leak an Rx (ie exponential blow up in cpu time), but is not caught at compile time
  //   def z() = Rx.unsafe { testZ += 1; a() }

  //   val yy = Rx.unsafe { a(); for (i <- 0 until 100) yield y() }
  //   val zz = Rx.unsafe { a(); for (i <- 0 until 100) yield z() }
  //   a() = 1
  //   a() = 2
  //   a() = 3
  //   a() = 4
  //   assert(testY == 500)
  //   assert(testZ == 1500)
  // }

  // it should "leakyObs" in {
  //   var testY = 0
  //   var testZ = 0
  //   val a     = Var(10)

  //   // Correct way to implement a def: Obs
  //   def y()(implicit zzz: Ctx.Owner) = a.foreach(_ => testY += 1)

  //   // This way will leak the Obs (ie exponential blow up in cpu time), but is not caught at compile time
  //   def z() = a.foreach(_ => testZ += 1)(Ctx.Owner.Unsafe)

  //   val yy = Rx.unsafe { a(); for (i <- 0 until 100) yield y() }
  //   val zz = Rx.unsafe { a(); for (i <- 0 until 100) yield z() }
  //   a() = 1
  //   a() = 2
  //   a() = 3
  //   a() = 4
  //   assert(testY == 500)
  //   assert(testZ == 1500)
  // }
}
