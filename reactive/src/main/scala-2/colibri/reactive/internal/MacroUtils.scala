package colibri.reactive.internal

import colibri.reactive.{Rx, NowOwner, LiveOwner}
import scala.reflect.macros._

// Inspired by scala.rx
object MacroUtils {

  def injectOwner[T](c: blackbox.Context)(src: c.Tree, newOwner: c.universe.TermName, replaces: Set[c.Type]): c.Tree = {
    import c.universe._

    val implicitOwnerAtCallers = replaces.map(c.inferImplicitValue(_, silent = false))

    object transformer extends c.universe.Transformer {
      override def transform(tree: c.Tree): c.Tree = {
        val shouldReplaceOwner = tree != null &&
          tree.isTerm &&
          implicitOwnerAtCallers.exists(_.tpe =:= tree.tpe) &&
          tree.tpe <:< typeOf[NowOwner] &&
          !(tree.tpe =:= typeOf[Nothing])

        if (shouldReplaceOwner) q"$newOwner"
        else super.transform(tree)
      }
    }
    transformer.transform(src)
  }

  def rxImpl[R](c: blackbox.Context)(f: c.Expr[R]): c.Expr[Rx[R]] = {
    import c.universe._

    val newOwner = c.freshName(TermName("colibriLiveOwner"))

    val newTree = c.untypecheck(
      injectOwner(c)(
        f.tree,
        newOwner,
        replaces = Set(typeOf[LiveOwner], typeOf[NowOwner]),
      ),
    )

    val tree = q"""
    _root_.colibri.reactive.Rx.function { ($newOwner: _root_.colibri.reactive.LiveOwner) =>
      $newTree
    }
    """

    // println(tree)

    c.Expr(tree)
  }
}
