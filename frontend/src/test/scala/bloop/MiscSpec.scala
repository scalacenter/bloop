package bloop

import bloop.testing.BaseSuite
import bloop.engine.ExecutionContext

import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.reactive.Consumer
import monix.reactive.Observable
import monix.reactive.MulticastStrategy

object MiscSpec extends BaseSuite {
  test("simulate file watching with consumer/producer monix patterns") {
    sealed trait TestStream
    object TestStream {
      case class InactiveStream(dropped: Long) extends TestStream
      case class ActiveStream(msgs: Seq[String]) extends TestStream
    }

    val (observer, observable) =
      Observable.multicast[String](MulticastStrategy.publish)(ExecutionContext.ioScheduler)

    val streamLog = new StringBuilder()
    val streamConsumer = Consumer.foreachTask { (s: TestStream) =>
      Task {
        s match {
          case TestStream.InactiveStream(dropped) =>
            //streamLog.synchronized {
            streamLog.++=(s"dropped ${dropped}").++=("\n")
          //}
          case TestStream.ActiveStream(msgs) =>
            if (msgs.nonEmpty) {

              //streamLog.synchronized {
              println("waiting in consumer")
              Thread.sleep(40)
              streamLog.++=(s"received ${msgs.sorted}").++=("\n")
            }
          //}
        }
        //streamLog.synchronized {
        streamLog.++=(s"finished task").++=("\n")
        //}
        ()
      }
    }

    import monix.reactive.OverflowStrategy
    import bloop.util.monix.BloopBufferTimedObservable
    val consumeTask = new BloopBufferTimedObservable(observable, FiniteDuration(40, "ms"))
      .map(es => TestStream.ActiveStream(es))
      .whileBusyDropEventsAndSignal(es => TestStream.InactiveStream(es))
      .consumeWith(streamConsumer)

    def push(msg: String) = {
      //streamLog.synchronized {
      streamLog.++=(s"pushing $msg").++=("\n")
      //}
      observer.onNext(msg)
    }

    val producerTask = Task {
      Thread.sleep(80)
      push("a")
      Thread.sleep(2)
      push("b")
      Thread.sleep(40)
      push("c")
      push("d")
      push("e")
      observer.onComplete()
    }

    val allTasks = Task.parMap2(consumeTask, producerTask)((_: Unit, _: Unit) => ())
    import scala.concurrent.Await
    try Await.result(allTasks.runToFuture(ExecutionContext.ioScheduler), FiniteDuration(1, "s"))
    catch { case t: Throwable => throw t } //pprint.log(streamLog.mkString); throw t }

    assertNoDiff(
      streamLog.mkString,
      """pushing a
        |
      """.stripMargin
    )
  }

  ignore("scastie") {
    import monix.execution.Ack
    import monix.execution.Ack.Continue
    import monix.execution.internal.Platform
    import monix.reactive.observers.Subscriber
    import monix.reactive.{Observable, Observer}
    import monix.reactive.MulticastStrategy
    import scala.concurrent.duration._
    import monix.execution.Scheduler.Implicits.global

    val (observer, observable) =
      Observable.multicast[String](MulticastStrategy.publish)
    import monix.eval.Task
    import monix.reactive.Consumer
    val received = new StringBuilder()
    val slowConsumer = Consumer.foreachTask { (msgs: Seq[String]) =>
      Task {
        Thread.sleep(40)
        received
          .++=("Received ")
          .++=(msgs.mkString(", "))
          .++=(System.lineSeparator)
        ()
      }
    }

    val consumingTask =
      observable.bufferTimed(40.millis).consumeWith(slowConsumer)
    val createEvents = Task {
      Thread.sleep(60)
      observer.onNext("a")
      observer.onNext("b")
      Thread.sleep(10)
      observer.onNext("c")
      Thread.sleep(20)
      observer.onNext("d")
      observer.onComplete()
      ()
    }

    val fut =
      Task.parMap2(consumingTask, createEvents)((_: Unit, _: Unit) => ()).runToFuture
    import scala.concurrent.Await
    Await.result(fut, 1.second)
    println(received.mkString)
    println("FINISHED")
  }
}
