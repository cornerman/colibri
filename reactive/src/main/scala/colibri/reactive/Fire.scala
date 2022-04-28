package colibri.reactive

trait Fire  {
  def commit(): Unit
  def forget(): Unit
}
object Fire {

  object Empty extends Fire {
    @inline def commit(): Unit = ()
    @inline def forget(): Unit = ()
  }

  @inline def empty: Fire = Empty

  private val emptyFun                     = () => ()
  def onCommit(onCommit: () => Unit): Fire = apply(onCommit = onCommit, onForget = emptyFun)
  def onForget(onForget: () => Unit): Fire = apply(onCommit = emptyFun, onForget = onForget)

  def apply(onCommit: () => Unit, onForget: () => Unit): Fire = new Fire {
    private var isDone = false

    @inline def commit(): Unit = if (!isDone) {
      isDone = true
      onCommit()
    }
    @inline def forget(): Unit = if (!isDone) {
      isDone = true
      onForget()
    }
  }

  @inline def composite(fires: Fire*): Fire              = compositeFromIterable(fires)
  def compositeFromIterable(fires: Iterable[Fire]): Fire = new Fire {
    @inline def commit(): Unit = fires.foreach(_.commit())
    @inline def forget(): Unit = fires.foreach(_.forget())
  }
}
