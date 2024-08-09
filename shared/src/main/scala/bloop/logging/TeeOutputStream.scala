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
