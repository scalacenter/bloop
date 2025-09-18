package bloop.io

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

import net.jpountz.xxhash.XXHashFactory
import scala.collection.mutable.ArrayBuilder

object ByteHasher {
  private final val seed = 0x9747b28c
  private final val hashFactory = XXHashFactory.fastestInstance()
  private[bloop] def hashBytes(bytes: Array[Byte]): Int = {
    val hashFunction = hashFactory.hash32()
    hashFunction.hash(ByteBuffer.wrap(bytes), seed)
  }
  def hashFileContents(file: File): (Int, Array[Byte]) = {

    val arrayBuilder = new ArrayBuilder.ofByte()
    val hash32 = hashFile(file, { bytes => arrayBuilder ++= bytes })
    (hash32, arrayBuilder.result())
  }
  def hashFile(file: File, onBytes: Array[Byte] => Unit = _ => ()): Int = {
    val hash32 = hashFactory.newStreamingHash32(seed)
    val channel = new RandomAccessFile(file, "r").getChannel()
    try {
      val length = 8092
      val buffer = ByteBuffer.allocate(length)
      val bytes = new Array[Byte](length)
      var read: Int = 0

      while ({
        read = channel.read(buffer);
        read != -1
      }) {
        buffer.flip()
        buffer.get(bytes, 0, read)
        hash32.update(bytes, 0, read)
        onBytes(bytes)
        buffer.clear()
      }

      hash32.getValue()
    } finally {
      channel.close()
    }
  }
}
