package bloop.util

import java.nio.ByteBuffer
import net.jpountz.xxhash.XXHashFactory

object ByteHasher {
  private final val seed = 0x9747b28c
  private final val hashFactory = XXHashFactory.fastestInstance()
  private[bloop] def hashBytes(bytes: Array[Byte]): Int = {
    val hashFunction = hashFactory.hash32()
    hashFunction.hash(ByteBuffer.wrap(bytes), seed)
  }
}
