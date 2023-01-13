[![Build Status](https://travis-ci.org/cornerman/colibri.svg?branch=master)](https://travis-ci.org/cornerman/colibri)

# Colibri

A simple functional reactive library for ScalaJS. Colibri is an implementation of the `Observable`, `Observer` and `Subject` reactive concepts.

If you're new to these concepts, here is a nice introduction from rx.js: <https://rxjs.dev/guide/overview>. Another good resource are these visualizations for common reactive operators: <https://rxmarbles.com/>.

This library includes:
- A (minimal and performant) reactive library based on JavaScript native operations like `setTimeout`, `setInterval`, `setImmediate`, `queueMicrotask`
- Typeclasses to integrate with other streaming libraries

## Usage

Reactive core library with typeclasses:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri" % "0.8.0"
```

```scala
import colibri._
```

Reactive variables with lazy, distinct, shared state variables (a bit like scala-rx):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-reactive" % "0.8.0"
```

```scala
import colibri.reactive._
```

For jsdom-based operations in the browser (`EventObservable`, `Storage`):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-jsdom" % "0.8.0"
```

```scala
import colibri.jsdom._
```

For scala.rx support (only Scala 2.x):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-rx" % "0.8.0"
```

```scala
import colibri.ext.rx._
```

For airstream support:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-airstream" % "0.8.0"
```

```scala
import colibri.ext.airstream._
```

For zio support:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-zio" % "0.8.0"
```

```scala
import colibri.ext.zio._
```

For fs2 support (`Source` only):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-fs2" % "0.8.0"
```

```scala
import colibri.ext.fs2._
```

## Subject, Observable and Observer

The implementation follows the reactive design:
- An observable is a stream to which you can subscribe with an Observer.
- An observer is basically a callback which can receive a value or an error from an Observable.
- A Subject is both an observable and an observer, receiving values and errors from the outside and distributing them to all subscribing observers.

Observables in colibri are lazy, that means nothing starts until you call `unsafeSubscribe` on an `Observable` (or any `unsafe*` method).

We integrate with effect types by means of typeclasses (see below). It provides support for `cats.effect.IO`, `cats.effect.SyncIO`, `cats.Eval`, `cats.effect.Resource` (out of the box) as well as `zio.Task` (with `outwatch-zio`).

Example Observables:
```scala
import colibri._
import scala.concurrent.duration._
import cats.effect.IO

val observable = Observable
  .interval(1.second)
  .mapEffect[IO](i => myCount(i))
  .distinctOnEquals
  .tapEffect[IO](c => myLog(c))
  .mapResource(x => myResource(x))
  .switchMap(x => myObservable(x))
  .debounceMillis(1000)

val observer = Observer.foreach[Int](println(_))

val subscription: Cancelable = observable.unsafeSubscribe(observer)

val subscriptionIO: IO[Cancelable] = observable.subscribeF[IO](observer)
```

Example Subjects:
```scala
import colibri._

val subject = Subject.publish[Int]() // or Subject.behavior(seed) or Subject.replayLast or Subject.replayAll

val subscription: Cancelable = subject.unsafeForeach(println(_))

subject.unsafeOnNext(1)

val myEffect: IO[Unit] = subject.onNextF[IO](2)
```

### Memory management

Every subscription that is created inside of colibri methods is returned to the user. For example `unsafeSubscribe` or `subscribeF` returns a `Cancelable`. That means, the caller is responsible to cleanup the subscription by calling `Cancelable#unsafeCancel()` or `Cancelable#cancelF`.

If you are working with `Outwatch`, you can just use `Observable` without ever subscribing yourself. Then all memory management is handled for you automatically. No memory leaks.

## Typeclasses

We have prepared typeclasses for integrating with other streaming libaries. The most important ones are `Sink` and `Source`. `Source` is a typeclass for Observables, `Sink` is a typeclass for Observers:

- `Sink[G[_]]` can send values and errors into `G` has an `onNext` and `onError` method.
- `Source[H[_]]` can unsafely subscribe to `H` with a `Sink` (returns a cancelable subscription)
- `CanCancel[T]` can cancel `T` to stop a subscription
- `LiftSink[G[_]]` can lift a `Sink` into type `G`
- `LiftSource[H[_]]` can lift a `Source` into type `H`
- `SubscriptionOwner[T]` can let type `T` own a subscription

In order to work with effects inside our Observable, we have defined the following two typeclasses similar to `Effect` in cats-effect 2:
- `RunEffect[F[_]]` can unsafely run an effect `F[_]` asynchronously, potentially starting synchronously until reaching an async boundary.
- `RunSyncEffect[F[_]]` can unsafely run an effect `F[_]` synchronously.

You can convert any `Source` into an `Observable` with `Observable.lift(source)`. The same for `Sink` and `Observer` with `Observer.lift(sink)`.

## Reactive variables

The module `colibri-reactive` exposes reactive variables. This is lazy, distinct shared state variables (internally using observables) that always have a value. These reactive variables are meant for managing state - opposed to managing events which is a perfect fit for lazy `Observable` in the core `colibri` library.

This module behaves similar to scala-rx - though variables are not hot and it is built on top of colibri Observables for seamless integration and powerful operators.

The whole thing is not entirely glitch-free, as invalid state can appear in operators like map or foreach. But you always have a consistent state in `now()` and it reduces the number of intermediate triggers or glitches. You can become completely glitch-free by converting back to observable and using `dropSyncGlitches` which will introduce an async boundary (micro-task).

A state variable is of type `Var[A] extends Rx[A] with RxWriter[A]`.

The laziness of variables means that the current value is only tracked if anyone subscribes to the `Rx[A]`. So an Rx does not compute anything on its own. You can still always call `now()` on it - if it is currently not subscribed, it will lazily calculate the current value.

Example:

```scala

import colibri.reactive._

val variable = Var(1)
val variable2 = Var("Test")

val rx = Rx {
  s"${variable()} - ${variable2()}"
}

val cancelable = rx.unsafeForeach(println(_))

println(variable.now()) // 1
println(variable2.now()) // "Test"
println(rx.now()) // "1 - Test"

variable.set(2) // println("2 - Test")

println(variable.now()) // 2
println(variable2.now()) // "Test"
println(rx.now()) // "2 - Test"

variable2.set("Foo") // println("2 - Foo")

println(variable.now()) // 2
println(variable2.now()) // "Foo"
println(rx.now()) // "2 - Foo"

cancelable.unsafeCancel()

println(variable.now()) // 2
println(variable2.now()) // "Foo"
println(rx.now()) // "2 - Foo"

variable.set(3) // no println

// now calculates new value lazily
println(variable.now()) // 3
println(variable2.now()) // "Foo"
println(rx.now()) // "3 - Foo"
```

[Outwatch](https://github.com/outwatch/outwatch) works perfectly with Rx - just like Observable.

```scala

import outwatch._
import outwatch.dsl._
import colibri.reactive._
import cats.effect.SyncIO

val component: VModifier = {
  val variable = Var(1)
  val mapped = rx.map(_ + 1)

  val rx = Rx {
    "Hallo: ${mapped()}"
  }

  div(rx)
}
```

There also exist `RxEvent` and `VarEvent`, which are event observables with shared execution. That is they behave like `Rx` and `Var` such that transformations are only applied once and not per subscription. But `RxEvent` and `VarEvent` are not distinct and have no current value. They should be used for event streams.

```
import colibri.reactive._

val variable = VarEvent[Int]()

val stream1 = RxEvent.empty
val stream2 = RxEvent.const(1)

val mapped = RxEvent.merge(variable.tap(println(_)).map(_ + 1), stream1, stream2)

val cancelable = mapped.unsafeForeach(println(_))
```

### Memory management

The same principles as for Observables hold. Any cancelable that is returned from the API needs to be handled by the the caller. Best practice: use subscribe/foreach as seldomly as possible - only in selected spots or within a library.

If you are working with `Outwatch`, you can just use `Rx` without ever subscribing yourself. Then all memory management is handled for you automatically. No memory leaks.

## Information

Throughout the library, the type parameters for the `Sink` and `Source` typeclasses are named consistenly to avoid naming ambiguity when working with `F[_]` in the same context:
- `F[_] : RunEffect`
- `G[_] : Sink`
- `H[_] : Source`

Source Code: [Source.scala](colibri/src/main/scala/colibri/Source.scala), [Sink.scala](colibri/src/main/scala/colibri/Sink.scala), [RunEffect.scala](colibri/src/main/scala/colibri/effect/RunEffect.scala)

In general, we take a middle ground with pure functional programming. We focus on performance and ease of use. Internally, the code is mutable for performance reasons. Externally, we try to expose a typesafe, immutable, and mostly pure interface to the user. There are some impure methods for example for subscribing observables - thereby potentially executing side effects. These impure methods are named `unsafe*`. And there are normally pure alias methods returning an effect type for public use. The unsafe methods exist so they can be used internally - we try to keep extra allocations to a minimum there.

Types like `Observable` are conceptionally very similar to `IO` - they can just return more than zero or one value. They are also lazy, and operations like map/flatMap/filter/... do not actually do anything. It is only after you unsafely run or subscribe an Observable that it actually starts evaluating.

[Implementation for rx](rx/src/main/scala/colibri/ext/rx/package.scala)

[Implementation for airstream](airstream/src/main/scala/colibri/ext/airstream/package.scala)

[Implementation for zio](zio/src/main/scala/colibri/ext/zio/package.scala)

[Implementation for fs2](fs2/src/main/scala/colibri/ext/fs2/package.scala)
