package bloop.logging

import java.io.PrintStream
import java.io.ByteArrayOutputStream
import scala.collection.concurrent.TrieMap

class TeeOutputStream(targetPS: PrintStream) extends PrintStream(targetPS) {

  private val allStreams = TrieMap.empty[Int, ByteArrayOutputStream];

  override def write(b: Int): Unit = {
    allStreams.values.foreach(
      _.write(b)
    )
    targetPS.write(b)
  }

  override def write(buf: Array[Byte], off: Int, len: Int): Unit = {
    allStreams.values.foreach(
      _.write(buf, off, len)
    )
    targetPS.write(buf, off, len)
  }

  override def flush(): Unit = {
    targetPS.flush()
  }

  def addListener(baos: ByteArrayOutputStream): Unit = {
    allStreams += baos.hashCode -> baos
  }

  def removeListener(baos: ByteArrayOutputStream): Unit = {
    allStreams -= baos.hashCode
  }
}

object TeeOutputStream {

  /**
   * Returns the tee on `System.out`, wrapping the current `System.out` when it is not one. Call it
   * before a Scala instance's class loader initializes `scala.Console`, which captures it once.
   */
  def systemOut(): TeeOutputStream = synchronized {
    System.out match {
      case tee: TeeOutputStream => tee
      case out =>
        val tee = new TeeOutputStream(out)
        System.setOut(tee)
        tee
    }
  }
}
