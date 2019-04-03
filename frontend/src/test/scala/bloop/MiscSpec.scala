package bloop

import bloop.testing.BaseSuite
import bloop.engine.ExecutionContext

import scala.concurrent.duration.FiniteDuration

import monix.reactive.Observable
import monix.reactive.MulticastStrategy

object MiscSpec extends BaseSuite {
  test("simulate file watching with consumer/producer monix patterns") {
    sealed trait TestStream
    object TestStream {
      case object Drop extends TestStream
      case class ActiveStream(msgs: Seq[String]) extends TestStream
    }

    val (observer, observable) =
      Observable.multicast[String](MulticastStrategy.replay)(ExecutionContext.ioScheduler)

    import monix.reactive.Consumer
    val streamLog = new StringBuilder()
    import monix.eval.Task
    val streamConsumer = Consumer.foreachAsync { (s: TestStream) =>
      Task {
        s match {
          case TestStream.Drop => streamLog.++=("received drop").++=("\n")
          case TestStream.ActiveStream(msgs) => streamLog.++=(s"received ${msgs.sorted}").++=("\n")
        }
        Thread.sleep(300)
      }
    }

    val consumeTask = observable
      .bufferTimed(FiniteDuration(20, "ms"))
      .map(es => TestStream.ActiveStream(es))
      .whileBusyDropEventsAndSignal(_ => TestStream.Drop)
      .consumeWith(streamConsumer)

    val producerTask = Task {
      observer.onNext("a")
      observer.onNext("b")
      Thread.sleep(100)
      observer.onNext("c")
      Thread.sleep(150)
      observer.onNext("d")
      observer.onNext("e")
      Thread.sleep(5)
      observer.onNext("f")
      Thread.sleep(10)
      observer.onNext("g")
      Thread.sleep(40)
      observer.onNext("h")
      Thread.sleep(80)
      observer.onNext("i")
      observer.onComplete()
    }

    val allTasks = Task.mapBoth(consumeTask, producerTask)((_: Unit, _: Unit) => ())
    import scala.concurrent.Await
    Await.result(allTasks.runAsync(ExecutionContext.ioScheduler), FiniteDuration(5, "s"))
    assertNoDiff(
      streamLog.mkString,
      """received List(a, b)
        |received drop
        |received drop
      """.stripMargin
    )
  }
}
