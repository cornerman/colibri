package colibri

import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class CancelableSpec extends AsyncFlatSpec with Matchers {

  "Cancelable Variable" should "update cancelled" in {
    val cancelable = Cancelable.variable()

    var outerInit   = 0
    var innerInit   = 0
    var outerCancel = 0
    var innerCancel = 0

    cancelable() = { () =>
      outerInit += 1

      cancelable() = { () =>
        innerInit += 1

        Cancelable { () =>
          innerCancel += 1
        }
      }

      Cancelable { () =>
        outerCancel += 1
      }
    }

    outerInit shouldBe 1
    outerCancel shouldBe 1
    innerInit shouldBe 1
    innerCancel shouldBe 0

    cancelable.unsafeCancel()

    outerInit shouldBe 1
    outerCancel shouldBe 1
    innerInit shouldBe 1
    innerCancel shouldBe 1
  }
}
