package bloop

import java.io.File
import java.net.URLClassLoader
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

  override val compilerJar: File =
    allJars
      .find(f =>
        f.getName.startsWith("scala-compiler-") && f.getName.endsWith(".jar"))
      .orNull

  override val libraryJar: File =
    allJars
      .find(f =>
        f.getName.startsWith("scala-library-") && f.getName.endsWith(".jar"))
      .orNull

  override val otherJars: Array[File] =
    allJars.filter(
      f =>
        f.getName.endsWith(".jar") && !(f.getName
          .startsWith("scala-compiler-") || f.getName.startsWith(
          "scala-library-")))

  override def actualVersion(): String =
    Option(loader.getResource("compiler.properties")).map { url =>
      val stream     = url.openStream()
      val properties = new Properties()
      properties.load(stream)
      properties.get("version.number").asInstanceOf[String]
    }.orNull
}

object ScalaInstance {
  def apply(scalaOrg: String,
            scalaName: String,
            scalaVersion: String): ScalaInstance = {
    val start = Resolution(
      Set(Dependency(Module(scalaOrg, scalaName), scalaVersion)))
    val repositories =
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))
    val fetch                                        = Fetch.from(repositories, Cache.fetch())
    val resolution                                   = start.process.run(fetch).unsafePerformSync
    //val errors: Seq[((Module, String), Seq[String])] = resolution.metadataErrors
    // TODO: Do something with the errors.
    val localArtifacts: Seq[FileError \/ File] = Task
      .gatherUnordered(
        resolution.artifacts.map(Cache.file(_).run)
      )
      .unsafePerformSync
    val allJars =
      localArtifacts.flatMap(_.toList).filter(_.getName.endsWith(".jar"))
    new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.toArray)
  }
}
