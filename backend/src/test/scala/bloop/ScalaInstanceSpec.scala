package bloop

import bloop.logging.RecordingLogger

import org.junit.Assert
import org.junit.Test
import org.junit.experimental.categories.Category
import bloop.io.AbsolutePath

@Category(Array(classOf[FastTests]))
class ScalaInstanceSpec {
  @Test def testInstanceFromBloop(): Unit = {
    val instance0 = ScalaInstance.scalaInstanceForJavaProjects(new RecordingLogger())
    Assert.assertTrue("Scala instance couldn't be created", instance0.isDefined)
    val instance = instance0.get
    try instance.loader.loadClass("scala.tools.nsc.Main$")
    catch {
      case _: ClassNotFoundException =>
        sys.error("Scala instance loader doesn't contain 'scala.tools.nsc.Main'.")
    }
    ()
  }

  @Test def creatingInstanceShouldNotThrow(): Unit = {
    ScalaInstance.apply(
      "org.scala-lang",
      "scala-compiler",
      "3.3.1",
      Seq(AbsolutePath("scala-compiler-3.3.1.jar")), // This jar doesn't exist
      new RecordingLogger()
    )

  }
}
