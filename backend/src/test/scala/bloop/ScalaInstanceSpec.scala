package bloop

import bloop.logging.RecordingLogger
import org.junit.{Assert, Test}
import org.junit.experimental.categories.Category

@Category(Array(classOf[FastTests]))
class ScalaInstanceSpec {
  @Test def testInstanceFromBloop(): Unit = {
    val instance0 = ScalaInstance.scalaInstanceFromBloop(new RecordingLogger())
    Assert.assertTrue("Scala instance couldn't be created", instance0.isDefined)
    val instance = instance0.get
    try instance.loader.loadClass("scala.tools.nsc.Main$")
    catch {
      case c: ClassNotFoundException =>
        sys.error("Scala instance loader doesn't contain 'scala.tools.nsc.Main'.")
    }
    ()
  }
}
