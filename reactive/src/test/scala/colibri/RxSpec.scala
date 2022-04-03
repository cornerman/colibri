package colibri.reactive

import colibri.{SubscriptionOwner, Cancelable}
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AsyncFlatSpec

class RxSpec extends AsyncFlatSpec with Matchers {

  implicit val subscriptionOwner: SubscriptionOwner[Assertion] = new SubscriptionOwner[Assertion] {
    def own(owner: Assertion)(subscription: () => Cancelable): Assertion = {
      subscription()
      owner
    }
  }

  "Rx" should "map with proper subscription lifetime" in Rx { implicit owner =>
    var mapped    = List.empty[Int]
    var received1 = List.empty[Int]
    var received2 = List.empty[Int]

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

  it should "map with proper subscription lifetime (onSubscribe)" in Rx.onSubscribe { implicit owner =>
    var mapped    = List.empty[Int]
    var received1 = List.empty[Int]

    val variable = Var(1)
    val stream   = variable.map { x => mapped ::= x; x }

    mapped shouldBe List.empty
    received1 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List.empty
    received1 shouldBe List.empty

    variable.set(2)

    mapped shouldBe List.empty
    received1 shouldBe List.empty

    val cancel1 = owner.unsafeSubscribe()

    mapped shouldBe List(2)
    received1 shouldBe List(2)

    variable.set(3)

    mapped shouldBe List(3, 2)
    received1 shouldBe List(3, 2)

    val cancel2 = owner.unsafeSubscribe()

    mapped shouldBe List(3, 2)
    received1 shouldBe List(3, 2)

    variable.set(4)

    mapped shouldBe List(4, 3, 2)
    received1 shouldBe List(4, 3, 2)

    cancel1.unsafeCancel()

    mapped shouldBe List(4, 3, 2)
    received1 shouldBe List(4, 3, 2)

    variable.set(5)

    mapped shouldBe List(5, 4, 3, 2)
    received1 shouldBe List(5, 4, 3, 2)

    cancel2.unsafeCancel()

    mapped shouldBe List(5, 4, 3, 2)
    received1 shouldBe List(5, 4, 3, 2)

    variable.set(6)

    mapped shouldBe List(5, 4, 3, 2)
    received1 shouldBe List(5, 4, 3, 2)
  }

  it should "be distinct" in Rx { implicit owner =>
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

  it should "filter" in Rx { implicit owner =>
    var mapped    = List.empty[Int]
    var received1 = List.empty[Int]
    var received2 = List.empty[Int]

    val variable = Var(1)
    val stream   = variable.filter { x => mapped ::= x; x % 2 != 0 }

    mapped shouldBe List(1)
    received1 shouldBe List.empty
    received2 shouldBe List.empty

    stream.foreach(received1 ::= _)

    mapped shouldBe List(1)
    received1 shouldBe List(1)
    received2 shouldBe List.empty

    variable.set(2)

    mapped shouldBe List(2, 1)
    received1 shouldBe List(1)
    received2 shouldBe List.empty

    variable.set(3)

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 1)
    received2 shouldBe List.empty

    stream.foreach(received2 ::= _)

    mapped shouldBe List(3, 2, 1)
    received1 shouldBe List(3, 1)
    received2 shouldBe List(3)

    variable.set(4)

    mapped shouldBe List(4, 3, 2, 1)
    received1 shouldBe List(3, 1)
    received2 shouldBe List(3)
  }.unsafeRunSync()
}
