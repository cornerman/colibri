[![Build Status](https://travis-ci.org/cornerman/colibri.svg?branch=master)](https://travis-ci.org/cornerman/colibri)

# Colibri - a simple functional reactive library for scala-js

Colibri is an implementation of the `Observable`, `Observer` and `Subject` reactive concepts.

If you're new to these, here is a nice introduction for rx.js: <https://rxjs.dev/guide/overview>.

Here you can find visualizations for common reactive operators: <https://rxmarbles.com/>

This library includes:
- a (minimal) frp library based on js-native operations like `setTimeout`, `setInterval`, `setImmediate`, `queueMicrotask`
- typeclasses for streaming to integrate with other streaming libraries

## Usage

```scala
libraryDependencies += "com.github.cornerman" %%% "colibri" % "0.3.2"
```

For scala.rx support (only Scala 2.x):
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-rx" % "0.3.2"
```

For airstream support:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-airstream" % "0.3.2"
```

For zio support:
```scala
libraryDependencies += "com.github.cornerman" %%% "colibri-zio" % "0.3.2"
```

```scala
import colibri._
import colibri.ext.rx._ //optional: colibri-rx
import colibri.ext.airstream._ // optional: colibri-airstream
import colibri.ext.zio._ // optional: colibri-zio
```

## Subject, Observable and Observer

The implementation follows the reactive design.
An observable is a stream to which you can subscribe with an Observer.
An observer is basically a callback which can receive a value or an error from an Observable.
A Subject is both an observables and an observer, receving values from the outside and distributing it all subscribing observers.

## Typeclasses

We have prepared typeclasses for integrating other streaming libaries:
- `Sink[G[_]]` can send values and errors into `G` has an `onNext` and `onError` method.
- `Source[H[_]]` can subscribe to `H` with a `Sink` (returns a cancelable subscription)
- `CanCancel[T]` can cancel `T` to stop a subscription
- `LiftSink[G[_]]` can lift a `Sink` into type `G`
- `LiftSource[H[_]]` can lift a `Source` into type `H`
- `SubscriptionOwner[T]` can let type `T` own a subscription

Most important here are `Sink` and `Source`. `Source` is a typeclass for Observables, `Sink` is a typeclass for Observers.

## Information

Throughout the library the type parameters for the `Sink` and `Source` typeclasses are named consistenly to avoid naming ambiguity when working with `F[_]` in the same context:
- `F[_] : Effect`
- `G[_] : Sink`
- `H[_] : Source`

Source Code: [Source.scala](colibri/src/main/scala/colibri/Source.scala), [Sink.scala](colibri/src/main/scala/colibri/Sink.scala)

[Implementation for Rx](rx/src/main/scala/colibri/ext/rx/package.scala)

[Implementation for Airstream](airstream/src/main/scala/colibri/ext/airstream/package.scala)

[Implementation for ZIO](zio/src/main/scala/colibri/ext/zio/package.scala)
