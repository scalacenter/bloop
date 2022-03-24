package bloop.config

import java.io.BufferedReader
import java.io.InputStreamReader
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
          reader.lines().collect(Collectors.joining(" "))
        } finally (isr.close())
      } finally stream.close()
    }
  }
}
