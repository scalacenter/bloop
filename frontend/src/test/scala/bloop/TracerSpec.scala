package bloop

import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

@Category(Array(classOf[bloop.FastTests]))
class TracerSpec {
  @Test
  def zipkin(): Unit = {
    import bloop.tracing.BraveTracer

    val tracer = BraveTracer("encode")
    Thread.sleep(2000)
    tracer.trace("previous children") { tracer =>
      Thread.sleep(1000)
      tracer.trace("inside children") { tracer =>
        Thread.sleep(1000)
      }
    }

    tracer.trace("next children") { tracer =>
      Thread.sleep(500)
    }
    Thread.sleep(2000)
    tracer.terminate()
  }
}
