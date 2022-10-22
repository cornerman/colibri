package colibri.reactive

import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec
import scala.concurrent.Promise

class ScalaRxAsyncTests extends AsyncFlatSpec with Matchers {

  import Owner.Unsafe._

  "async" should "basicExample" in {
    val p = Promise[Int]()
    val a = Rx.future(p.future)(10)

    assert(a.now() == 10)
    p.success(5)
    assert(a.now() == 5)
  }

  it should "repeatedlySendingOutFutures" in {
    var p = Promise[Int]()
    val a = Var(1)

    val b: Rx[Int] = Rx.function { implicit owner =>
      val f = Rx.future(p.future)(10)
      f() + a()
    }

    assert(b.now() == 11)
    p.success(5)
    assert(b.now() == 6)

    p = Promise[Int]()
    a() = 2
    assert(b.now() == 12)

    p.success(7)
    assert(b.now() == 9)
  }
}
