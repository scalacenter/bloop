package bloop

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

import coursier._

import scalaz.\/
import scalaz.concurrent.Task

class ScalaInstance(
    val organization: String,
    val name: String,
    override val version: String,
    override val allJars: Array[File],
) extends xsbti.compile.ScalaInstance {

  override lazy val loader: ClassLoader =
    new URLClassLoader(allJars.map(_.toURI.toURL), null)

  private def isJar(filename: String): Boolean = filename.endsWith(".jar")
  private def hasScalaCompilerName(filename: String): Boolean =
    filename.startsWith("scala-compiler")
  private def hasScalaLibraryName(filename: String): Boolean =
    filename.startsWith("scala-library")

  override val compilerJar: File =
    allJars.find(f => isJar(f.getName) && hasScalaCompilerName(f.getName)).orNull
  override val libraryJar: File =
    allJars.find(f => isJar(f.getName) && hasScalaLibraryName(f.getName)).orNull
  override val otherJars: Array[File] = allJars.filter { file =>
    val filename = file.getName
    isJar(filename) && !hasScalaCompilerName(filename) && !hasScalaLibraryName(filename)
  }

  /** Tells us what the real version of the classloaded scalac compiler in this instance is. */
  override def actualVersion(): String = {
    // TODO: Report when the `actualVersion` and the passed in version do not match.
    Option(loader.getResource("compiler.properties")).map { url =>
      val stream = url.openStream()
      val properties = new Properties()
      properties.load(stream)
      properties.get("version.number").asInstanceOf[String]
    }.orNull
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: ScalaInstance => other.hashCode == hashCode
    case _ => false
  }

  override val hashCode: Int = {
    val attributedJars =
      allJars.toSeq.map { jar =>
        val attrs = Files.readAttributes(jar.toPath, classOf[BasicFileAttributes])
        (jar, attrs.lastModifiedTime(), attrs.size())
      }
    attributedJars.hashCode()
  }
}

object ScalaInstance {
  import bloop.io.AbsolutePath

  /**
   * Reuses all jars to create an Scala instance if and only if all of them exist.
   *
   * This is done mainly by performance reasons, since dependency resolution is not
   * in the scope of what bloop is supposed to do. All resolution should be done by the user.
   *
   * When this is not the case, we resolve the Scala jars from coursier. This is good
   * because it means that if for some reason the scala jars do not exist, the user
   * will get no matter what get the right instance. If the jars don't exist and they
   * cannot be resolved, users will get a resolution error instead of a weird compilation
   * error when compilation via Zinc starts.
   */
  def apply(scalaOrg: String,
            scalaName: String,
            scalaVersion: String,
            allJars: Array[AbsolutePath]): ScalaInstance = {
    if (allJars.forall(j => Files.exists(j.underlying)))
      new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.map(_.toFile))
    else resolve(scalaOrg, scalaName, scalaVersion)
  }

  // Cannot wait to use opaque types for this
  type InstanceId = (String, String, String)
  import java.util.concurrent.ConcurrentHashMap
  private val instances = new ConcurrentHashMap[InstanceId, ScalaInstance]
  def resolve(scalaOrg: String, scalaName: String, scalaVersion: String): ScalaInstance = {
    def resolveInstance: ScalaInstance = {
      val start = Resolution(Set(Dependency(Module(scalaOrg, scalaName), scalaVersion)))
      val repositories = Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))
      val fetch = Fetch.from(repositories, Cache.fetch())
      val resolution = start.process.run(fetch).unsafePerformSync
      // TODO: Do something with the errors.
      //val errors: Seq[((Module, String), Seq[String])] = resolution.metadataErrors
      val localArtifacts: Seq[FileError \/ File] =
        Task.gatherUnordered(resolution.artifacts.map(Cache.file(_).run)).unsafePerformSync
      val allJars = localArtifacts.flatMap(_.toList).filter(_.getName.endsWith(".jar"))
      new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.toArray)
    }

    val instanceId = (scalaOrg, scalaName, scalaVersion)
    instances.computeIfAbsent(instanceId, _ => resolveInstance)
  }
}
