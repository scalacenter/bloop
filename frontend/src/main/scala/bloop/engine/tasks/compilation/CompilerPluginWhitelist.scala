package bloop.engine.tasks.compilation

import java.net.URI
import java.nio.file.{FileSystems, Files, Path, Paths}
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.util.concurrent.ConcurrentHashMap

import bloop.tracing.BraveTracer
import bloop.logging.{DebugFilter, Logger}
import monix.eval.Task

import scala.util.control.NonFatal
import scala.xml.XML

object CompilerPluginWhitelist {

  /**
   * A list of compiler plugin names (as specified in their scalac-plugin.xml
   * whose classloaders are safe to be cached. The plugin sources of every plugin
   * have been checked to ensure there is no global state and this is a requirement
   * to adding new names in the list.
   */
  val whitelistedPluginNames: List[String] = List(
    "bloop-test-plugin", // Plugin we use in tests
    "clippy",
    "scalajs",
    "nir", // scala-native
    "macro-paradise-plugin",
    "semanticdb",
    "wartremover",
    "silencer",
    "scapegoat",
    "acyclic",
    "scoverage",
    "kind-projector",
    "scalac-profiling",
    "classpath-shrinker",
    "bm4", // better-monadic-for
    "splain",
    "deriving"
  )

  /** A sequence of versions that are known not to support compiler plugin classloading. */
  val scalaVersionBlacklist = List("2.10.", "2.11.", "2.12.1", "2.12.2", "2.12.3", "2.12.4", "0.")

  private implicit val debug = DebugFilter.Compilation
  private val emptyMap = java.util.Collections.emptyMap[String, String]()
  private[this] val cachePluginJar = new ConcurrentHashMap[Path, (FileTime, Boolean)]()
  def enablePluginCaching(
      scalaVersion: String,
      scalacOptions: List[String],
      logger: Logger,
      tracer: BraveTracer
  ): Task[List[String]] = {
    def isPluginWhitelisted(pluginPath: Path): Boolean = {
      val uriZipFile = URI.create("jar:file:" + pluginPath.toUri.getRawPath)
      try {
        val fs = FileSystems.newFileSystem(uriZipFile, emptyMap)
        try {
          val pluginDeclarationFile = fs.getPath("/scalac-plugin.xml")
          val xml = XML.load(Files.newInputStream(pluginDeclarationFile))
          val pluginName = (xml \ "name").text
          val cache = whitelistedPluginNames.contains(pluginName)
          if (cache) logger.debug(s"Compiler plugin ${pluginName} is whitelisted")
          else logger.debug(s"Disabling plugin caching because ${pluginName} is not whitelisted")
          cache
        } finally {
          fs.close()
        }
      } catch {
        case NonFatal(t) =>
          logger.trace(t)
          logger.debug(s"Disable plugin caching because ${pluginPath} couldn't be read")
          false
      }
    }

    scalaVersionBlacklist.find(v => scalaVersion.startsWith(v)) match {
      case Some(blacklistedVersion) =>
        logger.debug(
          s"Disabling compiler plugin classloading, unsupported in Scala ${blacklistedVersion}"
        )
        Task.now(scalacOptions)
      case None =>
        if (scalacOptions.contains("-Ycache-plugin-class-loader:none")) Task.now(scalacOptions)
        else {
          val pluginCompilerFlags = scalacOptions.filter(_.startsWith("-Xplugin:"))
          def shouldCachePlugins(tracer: BraveTracer) = pluginCompilerFlags.map { flag =>
            Task {
              val jarList = flag.stripPrefix("-Xplugin:")
              jarList.split(java.io.File.pathSeparator).headOption match {
                case Some(mainPluginJar) =>
                  val pluginPath = Paths.get(mainPluginJar)
                  if (!Files.exists(pluginPath)) {
                    logger.debug(s"Disable plugin caching because ${pluginPath} doesn't exist")
                    false
                  } else {
                    val attrs = Files.readAttributes(pluginPath, classOf[BasicFileAttributes])
                    val lastModifiedTime = attrs.lastModifiedTime()
                    Option(cachePluginJar.get(pluginPath)) match {
                      case Some((prevLastModifiedTime, cacheClassloader))
                          if prevLastModifiedTime == lastModifiedTime =>
                        logger.debug(s"Cache hit ${cacheClassloader} for plugin ${pluginPath}")
                        cacheClassloader
                      case _ =>
                        tracer.trace(s"check whitelisted ${pluginPath}") { _ =>
                          logger.debug(s"Cache miss for plugin ${pluginPath}")
                          val shouldCache = isPluginWhitelisted(pluginPath)
                          cachePluginJar.put(pluginPath, (lastModifiedTime, shouldCache))
                          shouldCache
                        }
                    }
                  }
                case None =>
                  logger.debug(s"Expecting at least one jar in '$flag'")
                  false // The -Xplugin flag is misconstructed, don't cache
              }
            }
          }

          tracer.traceTask("enabling plugin caching") { tracer =>
            Task.gatherUnordered(shouldCachePlugins(tracer)).map(_.forall(_ == true)).map {
              enableCacheFlag =>
                if (!enableCacheFlag) scalacOptions
                else "-Ycache-plugin-class-loader:last-modified" :: scalacOptions
            }
          }
        }
    }
  }
}
