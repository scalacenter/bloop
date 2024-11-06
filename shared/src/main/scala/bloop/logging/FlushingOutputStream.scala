package bloop.logging

import java.io.ByteArrayOutputStream

class FlushingOutputStream(write: String => Unit) extends ByteArrayOutputStream {

  override def flush(): Unit = {
    write(this.toByteArray().toString())
  }

  override def close(): Unit = {
    flush();
    super.close();
  }

}
