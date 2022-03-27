package colibri.helpers

import scala.scalajs.js
import scala.scalajs.js.|

object NativeTypes {

  // See: https://www.scala-js.org/doc/interoperability/global-scope.html#dynamically-lookup-a-global-variable-given-its-name
  val globalObject: js.Dynamic = {
    import js.Dynamic.{global => g}
    if (js.typeOf(g.global) != "undefined" && (g.global.Object eq g.Object)) {
      // Node.js environment detected
      g.global
    } else {
      // In all other well-known environment, we can use the global `this`
      js.special.fileLevelThis.asInstanceOf[js.Dynamic]
    }
  }

  type SetImmediateHandle = Int
  type SetImmediate = js.Function1[js.Function0[Unit], SetImmediateHandle]
  type ClearImmediate = js.Function1[SetImmediateHandle, Unit]
  val (setImmediateRef, clearImmediateRef): (SetImmediate, ClearImmediate) = {
   if (js.typeOf(js.Dynamic.global.setImmediate) != "undefined")
      (js.Dynamic.global.setImmediate.bind(globalObject).asInstanceOf[SetImmediate], js.Dynamic.global.clearImmediate.bind(globalObject).asInstanceOf[ClearImmediate])
   else
      (js.Dynamic.global.setTimeout.bind(globalObject).asInstanceOf[SetImmediate], js.Dynamic.global.clearTimeout.bind(globalObject).asInstanceOf[ClearImmediate])
 }

  type QueueMicrotask = js.Function1[js.Function0[Unit], Unit]
  val queueMicrotask: QueueMicrotask = if (js.typeOf(js.Dynamic.global.queueMicrotask) != "undefined") {
    js.Dynamic.global.queueMicrotask.bind(globalObject).asInstanceOf[QueueMicrotask]
  } else {
    // Taken from: https://developer.mozilla.org/en-US/docs/Web/API/queueMicrotask#when_queuemicrotask_isnt_available
    (callback: js.Function0[Unit]) => {
      js.Promise.resolve[Unit](())
        .`then`[Unit]((_:Unit) => callback() : Unit | js.Thenable[Unit])
          .`catch`[Unit]({
            case t: Throwable =>
              js.timers.setTimeout(0)(throw t)
              ()
            case _ =>
              ()
        }: js.Function1[Any, Unit | js.Thenable[Unit]])

        ()
    }
  }
}
