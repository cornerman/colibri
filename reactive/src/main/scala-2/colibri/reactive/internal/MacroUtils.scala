package colibri.reactive.internal

import colibri.reactive.{Rx, Owner, LiveOwner}
import colibri.effect.SyncEmbed
import colibri.SubscriptionOwner
import scala.reflect.macros._

// Inspired by scala.rx
object MacroUtils {

  private val ownerName     = "colibriOwner"
  private val liveOwnerName = "colibriLiveOwner"

  def injectOwner[T](c: blackbox.Context)(src: c.Tree, newOwner: c.universe.TermName, exceptOwner: c.Type): c.Tree = {
    import c.universe._
    object transformer extends c.universe.Transformer {
      override def transform(tree: c.Tree): c.Tree = {
        val shouldReplaceOwner = tree != null &&
          tree.isTerm &&
          !(tree.toString.startsWith(ownerName + "$") || tree.toString.startsWith(liveOwnerName + "$")) &&
          tree.tpe <:< typeOf[Owner] &&
          !(tree.tpe =:= typeOf[Nothing]) &&
          !(tree.tpe <:< exceptOwner)

        if (shouldReplaceOwner) q"$newOwner"
        else super.transform(tree)
      }
    }
    transformer.transform(src)
  }

  def ownedImpl[R](
      c: blackbox.Context,
  )(f: c.Expr[R])(subscriptionOwner: c.Expr[SubscriptionOwner[R]], syncEmbed: c.Expr[SyncEmbed[R]]): c.Expr[R] = {
    import c.universe._

    val newOwner = c.freshName(TermName(ownerName))

    val newTree = c.untypecheck(injectOwner(c)(f.tree, newOwner, typeOf[LiveOwner]))

    val tree = q"""
    _root_.colibri.reactive.Owned.function { ($newOwner: _root_.colibri.reactive.Owner) =>
      $newTree
    }($subscriptionOwner, $syncEmbed)
    """

    // println(tree)

    c.Expr(tree)
  }

  def rxImpl[R](c: blackbox.Context)(f: c.Expr[R])(owner: c.Expr[Owner]): c.Expr[Rx[R]] = {
    import c.universe._

    val newOwner = c.freshName(TermName(liveOwnerName))

    val newTree = c.untypecheck(injectOwner(c)(f.tree, newOwner, typeOf[Nothing]))

    val tree = q"""
    _root_.colibri.reactive.Rx.function { ($newOwner: _root_.colibri.reactive.LiveOwner) =>
      $newTree
    }($owner)
    """

    // println(tree)

    c.Expr(tree)
  }
}
