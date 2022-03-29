[![Build Status](https://travis-ci.org/cornerman/colibri.svg?branch=master)](https://travis-ci.org/cornerman/colibri)

# Colibri

A simple functional reactive library for scala-js. Colibri is an implementation of the `Observable`, `Observer` and `Subject` reactive concepts.

If you're new to these, here is a nice introduction for rx.js: <https://rxjs.dev/guide/overview>.

Here you can find visualizations for common reactive operators: <https://rxmarbles.com/>

This library includes:
- a (minimal) frp library based on js-native operations like `setTimeout`, `setInterval`, `setImmediate`, `queueMicrotask`
- typeclasses for streaming to integrate with other streaming libraries

## Usage

```scala
libraryDependencies += "com.github.cornerman" %%% "colibri" % "0.4.0"
```

```scala
import colibri._
```

For scala.rx support (only Scala 2.x):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-rx" % "0.4.0"
```

```scala
import colibri.ext.rx._
```

For airstream support:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-airstream" % "0.4.0"
```

```scala
import colibri.ext.airstream._
```

For zio support:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-zio" % "0.4.0"
```

```scala
import colibri.ext.zio._
```

For fs2 support (`Source` only):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-fs2" % "0.4.0"
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

Example Observables:
```scala
import colibri._
import scala.concurrent.duration._
import cats.effect.IO

val observable = Observable
  .interval(1.second)
  .mapEffect[IO](i => count(i)
  .distinctOnEquals
  .tapEffect[IO](c => log(c))

val observer = Observer.foreach[Int](println(_))

observable.unsafeSubscribe(observer)

observable.subscribeF[IO](observer)
```

Example Subjects:
```scala
import colibri._

val subject = Subject.publish[Int]() // or Subject.behavior(seed) or Subject.replayLast or Subject.replayAll

subject.unsafeForeach(println(_))

subject.unsafeOnNext(1)

subject.onNextF[IO](2)
```

## Typeclasses

We have prepared typeclasses for integrating other streaming libaries:
- `Sink[G[_]]` can send values and errors into `G` has an `onNext` and `onError` method.
- `Source[H[_]]` can unsafely subscribe to `H` with a `Sink` (returns a cancelable subscription)
- `CanCancel[T]` can cancel `T` to stop a subscription
- `LiftSink[G[_]]` can lift a `Sink` into type `G`
- `LiftSource[H[_]]` can lift a `Source` into type `H`
- `SubscriptionOwner[T]` can let type `T` own a subscription

Most important here are `Sink` and `Source`. `Source` is a typeclass for Observables, `Sink` is a typeclass for Observers.

In order to work with effects inside our Observable, we have defined the following two typeclasses similar to `Effect` in cats-effect 2:
- `RunEffect[F[_]]` can unsafely run an effect `F[_]` asynchronously, potentially starting synchronously until reaching an async boundary.
- `RunSyncEffect[F[_]]` can unsafely run an effect `F[_]` synchronously.

## Information

Throughout the library the type parameters for the `Sink` and `Source` typeclasses are named consistenly to avoid naming ambiguity when working with `F[_]` in the same context:
- `F[_] : RunEffect`
- `G[_] : Sink`
- `H[_] : Source`

Source Code: [Source.scala](colibri/src/main/scala/colibri/Source.scala), [Sink.scala](colibri/src/main/scala/colibri/Sink.scala)

[Implementation for rx](rx/src/main/scala/colibri/ext/rx/package.scala)

[Implementation for airstream](airstream/src/main/scala/colibri/ext/airstream/package.scala)

[Implementation for zio](zio/src/main/scala/colibri/ext/zio/package.scala)

[Implementation for fs2](fs2/src/main/scala/colibri/ext/fs2/package.scala)
