package bloop.launcher

import java.io.PrintStream

import bloop.launcher.bsp.BspBridge
import bloop.testing.BaseSuite

object BspBridgeSpec extends BaseSuite {

  test("logs-recording") {
    val recorder = BspBridge.LogsRecordingStream(maxLines = 3)
    val printStream = new PrintStream(recorder)
    (0 to 10).foreach(i => printStream.println(i))
    printStream.print("last")

    assertEquals(
      recorder.logs.toList,
      List(
        "8",
        "9",
        "10",
        "last"
      )
    )
  }
}
