package com.martiansoftware.nailgun;

import java.io.InputStream;

final class BloopThreadLocalInputStream(stream: InputStream)
    extends ThreadLocalInputStream(stream) {
  override def init(streamForCurrentThread: InputStream): Unit =
    super.init(streamForCurrentThread)
}
