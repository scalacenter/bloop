package bloop.config

import java.io.InputStreamReader
import java.io.BufferedReader
import java.util.stream.Collectors

object TestPlatform {
  def getResourceAsString(resource: String): String = {
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    if (stream == null) sys.error(s"Missing resource $resource!")
    else {
      try {
        val isr = new InputStreamReader(stream)
        try {
          val reader = new BufferedReader(isr)
          reader.lines().collect(Collectors.joining(System.lineSeparator))
        } finally (isr.close())
      } finally stream.close()
    }
  }
}
