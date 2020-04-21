package bloop

import bloop.tracing.BraveTracer
import bloop.testing.BaseSuite
import bloop.tracing.TraceProperties
import monix.eval.Task

object TracerSpec extends BaseSuite {
  test("forty clients can send zipkin traces concurrently") {
    def sendTrace(id: String): Unit = {
      val tracer = BraveTracer(s"encode ${id}")
      Thread.sleep(700)
      tracer.trace(s"previous child ${id}") { tracer =>
        Thread.sleep(500)
        tracer.trace(s"inside child ${id}") { tracer =>
          Thread.sleep(500)
        }
      }

      tracer.trace(s"next children ${id}") { tracer =>
        Thread.sleep(250)
      }
      Thread.sleep(750)
      val tracer2 = tracer.toIndependentTracer(
        s"independent encode ${id}",
        TraceProperties.Global.properties
      )
      tracer.terminate()
      tracer2.trace(s"previous independent child ${id}") { _ =>
        Thread.sleep(750)
      }
      tracer2.terminate()
    }

    import scala.util.Random
    import scala.concurrent.duration.FiniteDuration
    def toDuration(ms: Int) = FiniteDuration(ms.toLong, "ms")
    def computeDelay = toDuration(Random.nextInt(4000))
    val delay1 = computeDelay
    val delay2 = computeDelay
    val delay3 = computeDelay
    val delay4 = computeDelay
    val delay5 = computeDelay
    val delay6 = computeDelay
    val delay7 = computeDelay
    val delay8 = computeDelay
    val delay9 = computeDelay
    val delay10 = computeDelay
    val delay11 = computeDelay
    val delay12 = computeDelay
    val delay13 = computeDelay
    val delay14 = computeDelay
    val delay15 = computeDelay
    val delay16 = computeDelay
    val delay17 = computeDelay
    val delay18 = computeDelay
    val delay19 = computeDelay

    val tasks = List(
      Task(sendTrace("1")),
      Task(sendTrace("2")).delayExecution(delay1),
      Task(sendTrace("3")).delayExecution(delay2),
      Task(sendTrace("4")).delayExecution(delay3),
      Task(sendTrace("5")).delayExecution(delay4),
      Task(sendTrace("6")).delayExecution(delay5),
      Task(sendTrace("7")).delayExecution(delay6),
      Task(sendTrace("8")).delayExecution(delay7),
      Task(sendTrace("9")).delayExecution(delay8),
      Task(sendTrace("10")).delayExecution(delay9),
      Task(sendTrace("11")).delayExecution(delay10),
      Task(sendTrace("12")).delayExecution(delay11),
      Task(sendTrace("13")).delayExecution(delay12),
      Task(sendTrace("14")).delayExecution(delay13),
      Task(sendTrace("15")).delayExecution(delay14),
      Task(sendTrace("16")).delayExecution(delay15),
      Task(sendTrace("17")).delayExecution(delay16),
      Task(sendTrace("18")).delayExecution(delay17),
      Task(sendTrace("19")).delayExecution(delay18),
      Task(sendTrace("20")).delayExecution(delay19),
      Task(sendTrace("21")).delayExecution(delay1),
      Task(sendTrace("22")).delayExecution(delay1),
      Task(sendTrace("23")).delayExecution(delay2),
      Task(sendTrace("24")).delayExecution(delay3),
      Task(sendTrace("25")).delayExecution(delay4),
      Task(sendTrace("26")).delayExecution(delay5),
      Task(sendTrace("27")).delayExecution(delay6),
      Task(sendTrace("28")).delayExecution(delay7),
      Task(sendTrace("29")).delayExecution(delay8),
      Task(sendTrace("30")).delayExecution(delay9),
      Task(sendTrace("31")).delayExecution(delay10),
      Task(sendTrace("32")).delayExecution(delay11),
      Task(sendTrace("33")).delayExecution(delay12),
      Task(sendTrace("34")).delayExecution(delay13),
      Task(sendTrace("35")).delayExecution(delay14),
      Task(sendTrace("36")).delayExecution(delay15),
      Task(sendTrace("37")).delayExecution(delay16),
      Task(sendTrace("38")).delayExecution(delay17),
      Task(sendTrace("39")).delayExecution(delay18),
      Task(sendTrace("40")).delayExecution(delay19)
    )

    import bloop.engine.ExecutionContext
    import bloop.util.TestUtil
    TestUtil.await(FiniteDuration(10, "s"), ExecutionContext.ioScheduler) {
      Task.gatherUnordered(tasks).map(_ => ())
    }
  }
}
