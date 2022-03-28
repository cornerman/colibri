package colibri.helpers

import scala.scalajs.js

private[colibri] object JSArrayHelper {
  def removeElement[T](array: js.Array[T])(element: T): Unit = {
    val index = array.indexOf(element)
    if (index != -1) array.splice(index, deleteCount = 1)
    ()
  }

  def removeElementCopied[T](array: js.Array[T])(element: T): js.Array[T] = {
    val index = array.indexOf(element)
    if (index != -1) {
      val newArray = js.Array[T]()
      var i        = 0
      while (i < array.length) {
        if (i != index) newArray.push(array(i))
        i += 1
      }
      newArray
    } else array
  }
}
