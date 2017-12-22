package bloop.logging

import java.io.{
  BufferedReader,
  ByteArrayOutputStream,
  ByteArrayInputStream,
  InputStreamReader,
  PrintStream
}
import java.util.UUID

import scala.collection.mutable

import org.junit.Test
import org.junit.Assert.assertEquals

class BloopLoggerSpec {
  @Test
  def infoAndWarnMessagesGoToOut =
    runAndCheck { logger =>
      logger.info("info")
      logger.warn("warn")
    } { (outMsgs, errMsgs) =>
      assertEquals(2, outMsgs.length.toLong)
      assertEquals(0, errMsgs.length.toLong)

      assert(isInfo(outMsgs.head))
      assert(isWarn(outMsgs.last))
    }

  @Test
  def errorMessagesGoToErr =
    runAndCheck { logger =>
      logger.error("error")
    } { (outMsgs, errMsgs) =>
      assertEquals(0, outMsgs.length.toLong)
      assertEquals(1, errMsgs.length.toLong)

      assert(isError(errMsgs.head))
    }

  @Test
  def debugAndTraceMessagesAreIgnoredByDefault =
    runAndCheck { logger =>
      logger.debug("debug")
      logger.trace(new Exception)
    } { (outMsgs, errMsgs) =>
      assertEquals(0, outMsgs.length.toLong)
      assertEquals(0, errMsgs.length.toLong)
    }

  @Test
  def debugAndTraceMessagesGoToErrInVerboseMode =
    runAndCheck { logger =>
      logger.error("error0")
      logger.warn("warn0")
      logger.info("info0")
      logger.debug("debug0")
      logger.trace(new Exception("trace0"))

      logger.verbose {
        logger.error("error1")
        logger.warn("warn1")
        logger.info("info1")
        logger.debug("debug1")
        logger.trace(new Exception("trace1"))
      }

      logger.error("error2")
      logger.warn("warn2")
      logger.info("info2")
      logger.debug("debug2")
      logger.trace(new Exception("trace2"))

    } { (outMsgs, errMsgs) =>
      assertEquals(6, outMsgs.length.toLong)
      assertEquals(5, errMsgs.length.toLong)

      assert(isWarn(outMsgs(0)) && outMsgs(0).endsWith("warn0"))
      assert(isInfo(outMsgs(1)) && outMsgs(1).endsWith("info0"))
      assert(isWarn(outMsgs(2)) && outMsgs(2).endsWith("warn1"))
      assert(isInfo(outMsgs(3)) && outMsgs(3).endsWith("info1"))
      assert(isWarn(outMsgs(4)) && outMsgs(4).endsWith("warn2"))
      assert(isInfo(outMsgs(5)) && outMsgs(5).endsWith("info2"))

      assert(isError(errMsgs(0)) && errMsgs(0).endsWith("error0"))
      assert(isError(errMsgs(1)) && errMsgs(1).endsWith("error1"))
      assert(isDebug(errMsgs(2)) && errMsgs(2).endsWith("debug1"))
      assert(isTrace(errMsgs(3)) && errMsgs(3).endsWith("java.lang.Exception: trace1"))
      assert(isError(errMsgs(4)) && errMsgs(4).endsWith("error2"))
    }

  @Test
  def multipleLoggersDontStepOnEachOtherToes = {
    val bos0 = new ByteArrayOutputStream
    val ps0 = new PrintStream(bos0)

    val bos1 = new ByteArrayOutputStream
    val ps1 = new PrintStream(bos1)

    val l0 = BloopLogger.at("l0", ps0, ps0)
    val l1 = BloopLogger.at("l1", ps1, ps1)

    l0.info("info0")
    l1.info("info1")

    val msgs0 = convertAndReadAllFrom(bos0)
    val msgs1 = convertAndReadAllFrom(bos1)

    assertEquals(1, msgs0.length.toLong)
    assertEquals(1, msgs1.length.toLong)
    assertEquals("info0", msgs0.head)
    assertEquals("info1", msgs1.head)
  }

  private def isWarn(msg: String): Boolean = msg.contains("[W]")
  private def isError(msg: String): Boolean = msg.contains("[E]")
  private def isDebug(msg: String): Boolean = msg.contains("[D]")
  private def isTrace(msg: String): Boolean = msg.contains("[T]")
  private def isInfo(msg: String): Boolean = {
    !(isWarn(msg) || isError(msg) || isDebug(msg) || isTrace(msg))
  }

  private def runAndCheck(op: BloopLogger => Unit)(
      check: (Seq[String], Seq[String]) => Unit): Unit = {
    val outStream = new ByteArrayOutputStream
    val errStream = new ByteArrayOutputStream

    val out = new PrintStream(outStream)
    val err = new PrintStream(errStream)

    val loggerName = UUID.randomUUID().toString
    val logger = BloopLogger.at(loggerName, out, err)
    op(logger)

    val outMessages = convertAndReadAllFrom(outStream)
    val errMessages = convertAndReadAllFrom(errStream)

    check(outMessages, errMessages)

  }

  private def convertAndReadAllFrom(stream: ByteArrayOutputStream): Seq[String] = {
    val inStream = new ByteArrayInputStream(stream.toByteArray)
    val reader = new BufferedReader(new InputStreamReader(inStream))

    val buffer = mutable.Buffer.empty[String]
    var current = ""
    while ({ current = reader.readLine(); current } != null) {
      buffer += current
    }

    buffer
  }
}
