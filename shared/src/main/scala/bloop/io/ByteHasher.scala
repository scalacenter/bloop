package bloop.io

import java.io.{File, RandomAccessFile}
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.file.{Path, Files}
import net.jpountz.xxhash.XXHashFactory

object ByteHasher {
  private final val seed = 0x9747b28c
  private final val hashFactory = XXHashFactory.fastestInstance()
  private[bloop] def hashBytes(bytes: Array[Byte]): Int = {
    val hashFunction = hashFactory.hash32()
    hashFunction.hash(ByteBuffer.wrap(bytes), seed)
  }

  def hashFileContents(file: File, userBytesArray: Option[Array[Byte]] = None): Int = {
    val hash32 = hashFactory.newStreamingHash32(seed)
    val channel = new RandomAccessFile(file, "r").getChannel()

    try {
      val length = userBytesArray.map(_.length).getOrElse(8092)
      val buffer = ByteBuffer.allocate(length)
      val bytes = userBytesArray.getOrElse(new Array[Byte](length))
      var read: Int = 0

      while ({
        read = channel.read(buffer);
        read != -1
      }) {
        buffer.flip()
        buffer.get(bytes, 0, read)
        hash32.update(bytes, 0, read)
        buffer.clear()
      }

      hash32.getValue()
    } finally {
      channel.close()
    }
  }
}
