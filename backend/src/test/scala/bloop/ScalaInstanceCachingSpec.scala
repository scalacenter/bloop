package bloop

import java.util.{Arrays, Collection}

import bloop.logging.BloopLogger
import org.junit.Assert.{assertSame, assertTrue}
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

object ScalaInstanceCachingSpec {
  val sameVersionPairs = Array("2.10.6", "2.10.1", "2.11.11", "2.12.0", "2.12.4")
  val unsharedVersionPairs =
    Array("2.10.6" -> "2.10.4", "2.11.11" -> "2.11.6", "2.12.4" -> "2.12.1")
  val logger = BloopLogger.default("test-logger")

  def getForVersion(version: String): ScalaInstance = {
    ScalaInstance.resolve("org.scala-lang", "scala-compiler", version, logger)
  }
}

@Category(Array(classOf[FastTests]))
@RunWith(classOf[Parameterized])
class SameScalaVersions(version: String) {
  @Test
  def sameScalaVersionsShareClassLoaders = {
    val instance1 = ScalaInstanceCachingSpec.getForVersion(version)
    val instance2 = ScalaInstanceCachingSpec.getForVersion(version)
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
    val instance1 = ScalaInstanceCachingSpec.getForVersion(v1)
    val instance2 = ScalaInstanceCachingSpec.getForVersion(v2)
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
