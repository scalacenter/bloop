package bloop

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.util.Properties
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import bloop.internal.build.BloopScalaInfo
import bloop.logging.{DebugFilter, Logger}

import scala.util.control.NonFatal
import sbt.internal.inc.BloopComponentCompiler

final class ScalaInstance private (
    val organization: String,
    val name: String,
    override val version: String,
    override val allJars: Array[File]
) extends xsbti.compile.ScalaInstance {

  override lazy val loaderCompilerOnly: ClassLoader =
    new URLClassLoader(compilerJars().map(_.toURI.toURL), ScalaInstance.topClassLoader)

  override def compilerJars(): Array[File] = {
    val all = allJars
      .filter(f => isJar(f.getName) && isCompilerJar(f))
    if (all.isEmpty) sys.error(s"Missing compiler jars in Scala jars ${allJars.mkString(", ")}")
    all
  }

  override def libraryJars(): Array[File] = {
    allJars
      .filter(f => isJar(f.getName) && hasScalaLibraryName(f.getName))
  }

  override val compilerJar: File = {
    allJars
      .find(f => isJar(f.getName) && hasScalaCompilerName(f.getName))
      .getOrElse(
        sys.error(s"Missing compiler jar in Scala jars ${allJars.mkString(", ")}")
      )
  }

  override val otherJars: Array[File] = allJars.filter { file =>
    val filename = file.getName
    isJar(filename) && !hasScalaCompilerName(filename) && !hasScalaLibraryName(filename)
  }

  /** Is this `ScalaInstance` using Dotty? */
  def isDotty: Boolean =
    (organization == "ch.epfl.lamp" && version.startsWith("0.")) ||
      (organization == "org.scala-lang" && version.startsWith("3."))

  override lazy val loaderLibraryOnly: ClassLoader =
    new URLClassLoader(libraryJars.map(_.toURI.toURL), ScalaInstance.topClassLoader)
  override lazy val loader: ClassLoader = {
    // For some exceptionally weird reason, we need to load all jars for dotty here
    val jarsToLoad = if (isDotty) allJars else allJars.filterNot(jar => libraryJars.contains(jar))
    new URLClassLoader(jarsToLoad.map(_.toURI.toURL), loaderLibraryOnly)
  }

  import ScalaInstance.ScalacCompilerName
  private def isJar(filename: String): Boolean = filename.endsWith(".jar")
  private def hasScalaCompilerName(filename: String): Boolean =
    filename.startsWith(ScalacCompilerName) ||
      (isDotty && (filename.startsWith("dotty-compiler") || filename.startsWith("scala3-compiler")))
  private def hasScalaLibraryName(filename: String): Boolean =
    filename.startsWith("scala-library") || filename.startsWith("scala3-library")

  private def hasScalaReflectName(filename: String): Boolean =
    filename.startsWith("scala-reflect")

  private def hasScalaXmlName(filename: String): Boolean =
    filename.startsWith("scala-xml")

  private def hasScala3AdditionalLibraryName(filename: String): Boolean =
    isDotty &&
      (filename.startsWith("scala3-interfaces") || filename.startsWith("tasty-core") ||
        filename.startsWith("scala-asm"))

  private def isCompilerJar(file: File) = {
    val name = file.getName()
    hasScalaReflectName(name) || hasScalaCompilerName(name) ||
    hasScalaLibraryName(name) || hasScalaXmlName(name) ||
    hasScala3AdditionalLibraryName(name)
  }

  /** Tells us what the real version of the classloaded scalac compiler in this instance is. */
  override def actualVersion(): String = {
    // TODO: Report when the `actualVersion` and the passed in version do not match.
    ScalaInstance.getVersion(loader)
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: ScalaInstance => other.hashCode == hashCode
    case _ => false
  }

  override val hashCode: Int = {
    val attributedJars =
      allJars.toSeq.map { jar =>
        val (lastModified, size) = {
          if (jar.exists()) (FileTime.fromMillis(0), 0)
          else {
            val attrs = Files.readAttributes(jar.toPath, classOf[BasicFileAttributes])
            (attrs.lastModifiedTime(), attrs.size())
          }
        }

        (jar, lastModified, size)
      }

    attributedJars.hashCode()
  }

  def supportsHydra: Boolean = compilerJar.getName().contains("-hydra")
}

object ScalaInstance {
  import bloop.io.AbsolutePath
  import scala.concurrent.ExecutionContext

  private[ScalaInstance] val bootClassLoader: ClassLoader = {
    if (!scala.util.Properties.isJavaAtLeast("9")) null
    else {
      try {
        MethodHandles
          .lookup()
          .findStatic(
            classOf[ClassLoader],
            "getPlatformClassLoader",
            MethodType.methodType(classOf[ClassLoader])
          )
          .invoke()
      } catch {
        case _: Throwable =>
          null
      }
    }
  }

  private[ScalaInstance] val topClassLoader: ClassLoader = {
    val bloopClassLoader = getClass.getClassLoader
    new ScalaInstanceTopLoader(bloopClassLoader, bootClassLoader)
  }

  private[ScalaInstance] final val ScalacCompilerName = "scala-compiler"

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
  def apply(
      scalaOrg: String,
      scalaName: String,
      scalaVersion: String,
      allJars: Seq[AbsolutePath],
      logger: Logger
  )(implicit ec: ExecutionContext): ScalaInstance = {
    val jarsKey = allJars.map(_.underlying).sortBy(_.toString).toList
    if (allJars.nonEmpty) {
      def newInstance = {
        logger.debug(s"Cache miss for scala instance ${scalaOrg}:${scalaName}:${scalaVersion}.")(
          DebugFilter.Compilation
        )
        jarsKey.foreach(p => logger.debug(s"  => $p")(DebugFilter.Compilation))
        new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.map(_.toFile).toArray)
      }

      val nonExistingJars = allJars.filter(j => !Files.exists(j.underlying))
      nonExistingJars.foreach(p => logger.warn(s"Scala instance jar ${p.syntax} doesn't exist!"))
      instancesByJar.computeIfAbsent(jarsKey, _ => newInstance)
    } else resolve(scalaOrg, scalaName, scalaVersion, logger)
  }

  // Cannot wait to use opaque types for this
  import java.util.concurrent.ConcurrentHashMap
  type InstanceId = (String, String, String)
  private val instancesById = new ConcurrentHashMap[InstanceId, ScalaInstance]
  private val instancesByJar = new ConcurrentHashMap[List[Path], ScalaInstance]
  def resolve(
      scalaOrg: String,
      scalaName: String,
      scalaVersion: String,
      logger: Logger
  )(implicit ec: ExecutionContext): ScalaInstance = {
    def resolveInstance: ScalaInstance = {
      val allPaths = DependencyResolution.resolve(
        List(DependencyResolution.Artifact(scalaOrg, scalaName, scalaVersion)),
        logger
      )
      val allJars = allPaths.collect {
        case path if path.underlying.toString.endsWith(".jar") => path.underlying.toFile
      }
      new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.toArray)
    }

    val instanceId = (scalaOrg, scalaName, scalaVersion)
    instancesById.computeIfAbsent(instanceId, _ => resolveInstance)
  }

  import java.util.concurrent.locks.ReentrantLock
  private[this] var cachedBloopLock = new ReentrantLock()
  private[this] var cachedBloopScalaInstance: Option[ScalaInstance] = null

  /**
   * Returns the default scala instance that is used in bloop's classloader.
   *
   * A Scala instance is always required to compile with Zinc. As a result,
   * Java projects that have no Scala configuration and want to be able to
   * re-use Zinc's Javac infrastructure for incremental compilation need to
   * have a dummy Scala instance available.
   *
   * This method is responsible for creating this dummy Scala instance. The
   * instance is fully functional and the jars for the instance come from Bloop
   * class loader which depends on all the Scala jars. Here we get the jars that are
   * usually used in scala jars from the protected domain of every class.
   *
   * This could not work in case a user has set a strict security manager in the
   * environment in which Bloop is running at. However, this is unlikely because
   * Bloop will run on different machines that normal production-ready machines.
   * In these machines, security managers don't usually exist and if they do don't
   * happen to be so strict as to prevent getting the location from the protected
   * domain.
   */
  def scalaInstanceForJavaProjects(
      logger: Logger
  )(implicit ec: ExecutionContext): Option[ScalaInstance] = {
    lazy val tempDirectory = Files.createTempDirectory("bloop-scala-instance")
    implicit val filter = DebugFilter.Compilation
    def findLocationForClazz(clazz: Class[_], jarName: String): Option[Path] = {
      try {
        val expectedPath = clazz.getProtectionDomain.getCodeSource.getLocation.toURI
        logger.debug(s"${clazz} detected in ${expectedPath}")

        try Some(Paths.get(expectedPath))
        catch {
          case t: java.nio.file.FileSystemNotFoundException =>
            // When bloop is bootstrapped by coursier, jars are available from resources instead
            logger.debug(
              s"Load jar from resource because scheme '${expectedPath.getScheme}' has no file system provider"
            )

            val fromResourceStream =
              clazz.getResourceAsStream("/" + expectedPath.getSchemeSpecificPart)
            if (fromResourceStream == null) {
              val scalaJarResourceError =
                """Unexpected error when creating Bloop's default Scala instance!
                  |  -> The resources where Bloop Scala jars are hosted cannot be accessed
                  |This error can happen when making an standalone bootstrap of bloop via coursier which is currently not supported
                """.stripMargin
              logger.error(scalaJarResourceError)
              None
            } else {
              val outPath = tempDirectory.resolve(jarName)
              logger.debug(s"${clazz} detected in resource, dumping to ${outPath}...")
              Files.copy(fromResourceStream, outPath)
              Some(outPath)
            }
        }
      } catch {
        case NonFatal(t) =>
          logger.debug("Unexpected error when creating Bloop's classloader")
          logger.trace(t)
          None
      }
    }

    if (cachedBloopScalaInstance != null) {
      cachedBloopScalaInstance
    } else {
      cachedBloopLock.lock()
      try {
        logger.debug("Creating a scala instance from Bloop's classloader...")
        val instance = {
          for {
            libraryJar <- findLocationForClazz(scala.Predef.getClass, "scala-library.jar")
            treesClazz = classOf[scala.reflect.api.Trees]
            reflectJar <- findLocationForClazz(treesClazz, "scala-reflect.jar")
            compilerJar <- findLocationForClazz(scala.tools.nsc.Main.getClass, "scala-compiler.jar")
            xmlJar <- findLocationForClazz(classOf[scala.xml.Node], "scala-xml.jar")
            jlineJar <- findLocationForClazz(classOf[jline.console.ConsoleReader], "jline.jar")
          } yield {
            logger.debug(s"Created Bloop scala instance for ${BloopScalaInfo.scalaVersion}")
            val jars = List(libraryJar, reflectJar, compilerJar, xmlJar, jlineJar)
            ScalaInstance(
              BloopScalaInfo.scalaOrganization,
              ScalacCompilerName,
              BloopScalaInfo.scalaVersion,
              jars.map(AbsolutePath(_)),
              logger
            )
          }
        }

        cachedBloopScalaInstance = instance
        instance
      } finally cachedBloopLock.unlock()
    }
  }

  def getVersion(loader: ClassLoader): String = {
    val version = Option(loader.getResource("compiler.properties")).flatMap { url =>
      val stream = url.openStream()
      val properties = new Properties()
      properties.load(stream)
      Option(properties.get("version.number").asInstanceOf[String])
    }
    version.getOrElse(s"Loader $loader doesn't have Scala in it!")
  }
}
