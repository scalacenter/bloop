package bloop

import java.util.{Arrays, Collection}

import bloop.logging.BloopLogger
import org.junit.Assert.{assertSame, assertTrue}
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

object ScalaInstanceCachingSpec {
  val sameVersionPairs = Array("2.10.6", "2.10.1", "2.11.11", "2.12.0", "2.12.4")
  val unsharedVersionPairs =
    Array("2.10.6" -> "2.10.4", "2.11.11" -> "2.11.6", "2.12.4" -> "2.12.1")
  val logger = BloopLogger.default("test-logger")
}

@RunWith(classOf[Parameterized])
class SameScalaVersions(version: String) {
  @Test
  def sameScalaVersionsShareClassLoaders = {
    val instance1 = ScalaInstance.resolve("org.scala-lang",
                                          "scala-compiler",
                                          version.toString,
                                          ScalaInstanceCachingSpec.logger)
    val instance2 = ScalaInstance.resolve("org.scala-lang",
                                          "scala-compiler",
                                          version.toString,
                                          ScalaInstanceCachingSpec.logger)
    assertSame(s"Failed with version = $version", instance1.loader, instance2.loader)
  }
}
object SameScalaVersions {
  @Parameters
  def data(): Collection[Array[String]] = {
    Arrays.asList(ScalaInstanceCachingSpec.sameVersionPairs.map(Array.apply(_)): _*)
  }
}

@RunWith(classOf[Parameterized])
class DifferentScalaVersions(v1: String, v2: String) {
  @Test
  def differentVersionSameMajorNumberDontShareClassLoaders = {
    val instance1 =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", v1, ScalaInstanceCachingSpec.logger)
    val instance2 =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", v2, ScalaInstanceCachingSpec.logger)
    assertTrue(s"Failed with v1 = $v1, v2 = $v2", instance1.loader != instance2.loader)
  }
}
object DifferentScalaVersions {
  @Parameters
  def data(): Collection[Array[String]] = {
    Arrays.asList(ScalaInstanceCachingSpec.unsharedVersionPairs.map {
      case (v1, v2) => Array(v1, v2)
    }: _*)
  }
}
