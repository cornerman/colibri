[![Build Status](https://travis-ci.org/cornerman/colibri.svg?branch=master)](https://travis-ci.org/cornerman/colibri)

# Colibri - a simple functional reactive library for scala-js

Usage:
```scala
libraryDependencies += "com.github.cornerman.colibri" %%% "colibri" % "0.1.1"
```

For monix support:
```scala
libraryDependencies += "com.github.cornerman.colibri" %%% "colibri-monix" % "0.1.1"
```

For scala.rx support:
```scala
libraryDependencies += "com.github.cornerman.colibri" %%% "colibri-rx" % "0.1.1"
```

This library includes a minimal frp library and typeclasses for streaming.

We have prepared typeclasses for integrating other streaming libaries:
- `Sink[G[_]]` can send values and errors into `G` has an `onNext` and `onError` method.
- `Source[H[_]]` can subscribe to `H` with a `Sink` (returns a cancelable subscription)
- `CanCancel[T]` can cancel `T` to stop a subscription
- `SubscriptionOwner[T]` can let type `T` own a subscription
- `LiftSink[G[_]]` can lift a `Sink` into type `G`
- `LiftSource[H[_]]` can lift a `Source` into type `H`
- `CreateSubject[GH[_]]` how to create subject in `GH`
- `CreateProHandler[GH[_,_]]` how to create subject in `GH` which has differnt input/output types.

Most important here are `Sink` and `Source`. `Source` is a typeclass for Observables, `Sink` is a typeclass for Observers.

Throughout the library the type parameters for the `Sink` and `Source` typeclasses are named consistenly to avoid naming ambiguity when working with `F[_]` in the same context:
- `F[_] : Effect`
- `G[_] : Sink`
- `H[_] : Source`

Source Code: [Source.scala](colibri/src/main/scala/colibri/Source.scala), [Sink.scala](colibri/src/main/scala/colibri/Sink.scala)

[Implmentation for Monix](monix/src/main/scala/colibri/ext/monix/package.scala)

[Implmentation for Rx](rx/src/main/scala/colibri/ext/rx/package.scala)
