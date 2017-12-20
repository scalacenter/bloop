package bloop.logging

import org.junit.Test
import org.junit.Assert.assertEquals

class ProcessLoggerSpec {

  @Test
  def loggerToOutputStream = {
    val logger = new RecordingLogger
    val stream = ProcessLogger.toOutputStream(logger.info)
    stream.write("hello".getBytes("UTF-8"))
    stream.write("world".getBytes("UTF-8"))
    stream.flush()

    val messages = logger.getMessages

    assertEquals(1, messages.length.toLong)
    assertEquals(messages.head, ("info", "helloworld"))
  }

  @Test
  def loggerToPrintStream = {
    val logger = new RecordingLogger
    val stream = ProcessLogger.toPrintStream(logger.info)
    stream.println("hello")
    stream.println("world")
    stream.println()

    val messages = logger.getMessages

    assertEquals(3, messages.length.toLong)
    assertEquals(messages.head, ("info", "hello"))
    assertEquals(messages.tail.head, ("info", "world"))
    assertEquals(messages.last, ("info", ""))
  }

}
