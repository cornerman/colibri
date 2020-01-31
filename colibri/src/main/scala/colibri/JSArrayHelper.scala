package colibri

import scala.scalajs.js

private[colibri] object JSArrayHelper {
  def removeElement[T](array: js.Array[T])(element: T): Unit = {
    val index = array.indexOf(element)
    if (index != -1) array.splice(index, deleteCount = 1)
    ()
  }
}
