package bloop.util
import java.nio.ByteBuffer
import java.util.{Base64, UUID}

object UUIDUtil {

  // Copied from https://stackoverflow.com/a/29836273

  def toBase64String(uuid: UUID): String = {
    val bb = ByteBuffer.wrap(new Array[Byte](16))
    bb.putLong(uuid.getMostSignificantBits)
    bb.putLong(uuid.getLeastSignificantBits)
    Base64.getUrlEncoder.encodeToString(bb.array)
  }

  def randomUUID: String = toBase64String(UUID.randomUUID)

}
