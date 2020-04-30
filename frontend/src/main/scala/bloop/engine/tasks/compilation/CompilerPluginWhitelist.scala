package bloop.engine.tasks.compilation

import java.net.URI
import java.nio.file.{FileSystems, Files, Path, Paths}
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.util.concurrent.ConcurrentHashMap

import bloop.tracing.BraveTracer
import bloop.logging.{DebugFilter, Logger}
import bloop.engine.ExecutionContext

import monix.eval.Task
import monix.reactive.{Observable, Consumer}

import scala.xml.XML
import scala.concurrent.Promise
import scala.collection.mutable
import scala.util.control.NonFatal

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
  private[this] val pluginPromises = new ConcurrentHashMap[String, Promise[Boolean]]()
  private[this] val cachePluginJar = new ConcurrentHashMap[Path, (FileTime, Boolean)]()

  /**
   * Enable compiler plugin caching in scalac options if the feature is
   * supported in the given scala version and all the compiler plugins in a
   * project are marked as safe in Bloop's compiler plugin whitelist.
   *
   * This is a method that can be called concurrently by different compiler
   * processes that share the same compiler option, so it needs to be
   * thread-safe. To compute whether a compiler plugin is friendly to caching
   * or not, we first try to acquire the scalac option representing that
   * compiler plugin and then process that in parallel. If we cannot acquire
   * the processing of a given compiler plugin, it means another concurrent
   * process is doing it, so before returning we wait on those background
   * computations. Once all the compiler plugins have been populated, we check
   * if all compiler plugins are cache friendly and, if that's the case, we add
   * a scalac option to cache plugin classloaders.
   */
  def enableCachingInScalacOptions(
      scalaVersion: String,
      scalacOptions: List[String],
      logger: Logger,
      tracer: BraveTracer,
      parallelUnits: Int
  ): Task[List[String]] = Task.defer {
    case class WorkItem(pluginFlag: String, idx: Int, result: Promise[Boolean])

    val actualScalaVersion = scalaVersion.split('-').headOption
    val blacklistedVersions = scalaVersionBlacklist.find { v =>
      actualScalaVersion.exists { userVersion =>
        if (v.endsWith(".")) userVersion.startsWith(v) else userVersion == v
      }
    }

    val enableTask = blacklistedVersions match {
      case Some(blacklistedVersion) =>
        logger.debug(s"Disabled compiler plugin classloading, unsupported in ${blacklistedVersion}")
        Task.now(scalacOptions)
      case None =>
        if (scalacOptions.contains("-Ycache-plugin-class-loader:none")) Task.now(scalacOptions)
        else {
          val pluginCompilerFlags = scalacOptions.iterator.filter(_.startsWith("-Xplugin:")).toArray
          // Use an array with the same indices to be able to update results in a thread-safe way
          val cachePluginResults = new Array[Boolean](pluginCompilerFlags.size)

          tracer.traceTaskVerbose("enabling plugin caching") { tracer =>
            // Consumers are processed with `foreachParallel`, so we side-effect on `cachePluginResults`
            val parallelConsumer = {
              Consumer.foreachParallelAsync[WorkItem](parallelUnits) {
                case WorkItem(pluginCompilerFlag, idx, p) =>
                  shouldCachePlugin(pluginCompilerFlag, tracer, logger).materialize.map {
                    case scala.util.Success(cache) =>
                      p.success(cache)
                      pluginPromises.remove(pluginCompilerFlag)
                      cachePluginResults(idx) = cache
                    case scala.util.Failure(t) => p.failure(t)
                  }
              }
            }

            val acquiredByOtherTasks = new mutable.ListBuffer[Task[Unit]]()
            val acquiredByThisInvocation = new mutable.ListBuffer[WorkItem]()

            // Acquire or stash a work item if it was picked up by another process
            pluginCompilerFlags.zipWithIndex.foreach {
              case (pluginCompilerFlag, idx) =>
                val shouldCachePromise = Promise[Boolean]()
                val promise = pluginPromises.putIfAbsent(pluginCompilerFlag, shouldCachePromise)
                if (promise != null) {
                  acquiredByOtherTasks.+=(Task.fromFuture(promise.future).map { cache =>
                    cachePluginResults(idx) = cache
                  })
                } else {
                  acquiredByThisInvocation.+=(WorkItem(pluginCompilerFlag, idx, shouldCachePromise))
                }
            }

            Observable
              .fromIterable(acquiredByThisInvocation.toList)
              .consumeWith(parallelConsumer)
              .flatMap { _ =>
                // Then, we block on those tasks that were picked up by different invocations
                val blockingBatches = {
                  acquiredByOtherTasks.toList
                    .grouped(parallelUnits)
                    .map(group => Task.gatherUnordered(group))
                }

                Task.sequence(blockingBatches).map(_.flatten).map { _ =>
                  val enableCacheFlag = cachePluginResults.forall(_ == true)
                  if (!enableCacheFlag) scalacOptions
                  else "-Ycache-plugin-class-loader:last-modified" :: scalacOptions
                }
              }
          }
        }
    }

    enableTask.executeOn(ExecutionContext.ioScheduler).materialize.map {
      case scala.util.Success(options) => options
      case scala.util.Failure(f) =>
        logger.debug("Enabling the plugin whitelist failed! Disabling it.")
        logger.trace(f)
        scalacOptions
    }
  }

  private def shouldCachePlugin(
      pluginCompilerFlag: String,
      tracer: BraveTracer,
      logger: Logger
  ): Task[Boolean] = {
    // Note we use eval here because the consumer makes sure it gets its own thread
    Task.eval {
      val jarList = pluginCompilerFlag.stripPrefix("-Xplugin:")
      jarList.split(java.io.File.pathSeparatorChar).headOption match {
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
                  val shouldCache = isPluginWhitelisted(pluginPath, logger)
                  cachePluginJar.put(pluginPath, (lastModifiedTime, shouldCache))
                  shouldCache
                }
            }
          }
        case None =>
          logger.debug(s"Expecting at least one jar in '$pluginCompilerFlag'")
          false // The -Xplugin flag is misconstructed, don't cache
      }
    }
  }

  private def isPluginWhitelisted(pluginPath: Path, logger: Logger): Boolean = {
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
}
