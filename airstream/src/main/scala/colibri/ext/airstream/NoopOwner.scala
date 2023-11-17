package com.raquo.airstream.ownership.internalcolibri

import com.raquo.airstream.ownership._
import com.raquo.ew.JsArray

object NoopOwner extends Owner {
  override protected[this] val subscriptions: JsArray[Subscription]                    = null
  override protected[this] def killSubscriptions(): Unit                               = ()
  override protected[this] def onOwned(subscription: Subscription): Unit               = ()
  override private[ownership] def onKilledExternally(subscription: Subscription): Unit = ()
  override private[ownership] def own(subscription: Subscription): Unit                = ()
}
