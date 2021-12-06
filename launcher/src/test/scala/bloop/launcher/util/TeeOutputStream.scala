package bloop.launcher.util

import java.io.OutputStream

// adapted from https://stackoverflow.com/a/7987606/3714539
final class TeeOutputStream(
    out: OutputStream,
    tee: OutputStream
) extends OutputStream {

  override def write(b: Int): Unit = {
    out.write(b)
    tee.write(b)
  }

  override def write(b: Array[Byte]): Unit = {
    out.write(b)
    tee.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    out.write(b, off, len)
    tee.write(b, off, len)
  }

  override def flush(): Unit = {
    out.flush()
    tee.flush()
  }

  override def close(): Unit = {
    out.close()
  }
}
