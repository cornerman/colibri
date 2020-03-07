[![Build Status](https://travis-ci.org/cornerman/colibri.svg?branch=master)](https://travis-ci.org/cornerman/colibri)

# Colibri - a simple functional reactive library scala-js

This library includes a minimal frp library and typeclasses for streaming.

We have prepared typeclasses for integrating other streaming libaries:
- `Sink[F[_]]` can send values and errors into `F` has an `onNext` and `onError` method.
- `Source[F[_]]` can subscribe to `F` with a `Sink` (returns a cancelable subscription)
- `CanCancel[T]` can cancel `T` to stop a subscription
- `SubscriptionOwner[T]` can let type `T` own a subscription
- `LiftSink[F[_]]` can lift a `Sink` into type `F`
- `LiftSource[F[_]]` can lift a `Source` into type `F`
- `CreateHandler[F[_]]` how to create subject in `F`
- `CreateProHandler[F[_,_]]` how to create subject in `F` which has differnt input/output types.

Most important here are `Sink` and `Source`. `Source` allows you use your observable as a `VDomModifier`. `Sink` allows you to use your observer on the right-hand side of `-->`.

Source Code: [Source.scala](colibri/src/main/scala/colibri/Source.scala), [Sink.scala](colibri/src/main/scala/colibri/Sink.scala)

Example: [Implmentation for Monix](monix/src/main/scala/colibri/ext/monix/package.scala)
