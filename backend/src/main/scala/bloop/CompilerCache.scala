package bloop

import java.io.File
import java.lang.Iterable
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import javax.tools.JavaFileManager.Location
import javax.tools.JavaFileObject.Kind
import javax.tools.{
  FileObject,
  ForwardingJavaFileManager,
  StandardJavaFileManager,
  ForwardingJavaFileObject,
  JavaFileManager,
  JavaFileObject,
  JavaCompiler => JavaxCompiler
}

import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger

import sbt.librarymanagement.Resolver

import xsbti.ComponentProvider
import xsbti.compile.Compilers
import xsbti.compile.{JavaCompiler => XJavaCompiler, JavaTool => XJavaTool}
import xsbti.compile.ClassFileManager
import xsbti.{Logger => XLogger, Reporter => XReporter}

import sbt.internal.inc.bloop.ZincInternals
import sbt.internal.inc.{AnalyzingCompiler, ZincLmUtil, ZincUtil}
import sbt.internal.inc.javac.JavaTools
import sbt.internal.inc.javac.{JavaCompiler, Javadoc, ForkedJava}
import sbt.internal.util.LoggerWriter
import sbt.internal.inc.javac.DiagnosticsReporter
import java.io.IOException

final class CompilerCache(
    componentProvider: ComponentProvider,
    retrieveDir: AbsolutePath,
    logger: Logger,
    userResolvers: List[Resolver]
) {

  private case class CacheKey(javaInstance: JavaInstance, scalaInstance: ScalaInstance)

  private val cache = new ConcurrentHashMap[CacheKey, Compilers]()

  def get(javaInstance: JavaInstance, scalaInstance: ScalaInstance): Compilers = {
    val key = CacheKey(javaInstance, scalaInstance)
    cache.computeIfAbsent(key, newCompilers)
  }

  private[bloop] def duplicateWith(logger: Logger): CompilerCache =
    new CompilerCache(componentProvider, retrieveDir, logger, userResolvers)

  private def newCompilers(key: CacheKey): Compilers = {
    val scalaCompiler = getScalaCompiler(key.scalaInstance, componentProvider)
    val javaCompiler = getForkedJavaCompiler(key.javaInstance.javaHome.toString)
      .orElse {
        Option(javax.tools.ToolProvider.getSystemJavaCompiler)
          .map(compiler => new BloopJavaCompiler(compiler))
      }
      .getOrElse(new BloopForkedJavaCompiler(None))

    val javaDoc = Javadoc.local.getOrElse(Javadoc.fork())
    val javaTools = JavaTools(javaCompiler, javaDoc)
    ZincUtil.compilers(javaTools, scalaCompiler)
  }

  def getScalaCompiler(
      scalaInstance: ScalaInstance,
      componentProvider: ComponentProvider
  ): AnalyzingCompiler = {
    val bridgeSources = ZincInternals.getModuleForBridgeSources(scalaInstance)
    val bridgeId = ZincInternals.getBridgeComponentId(bridgeSources, scalaInstance)
    componentProvider.component(bridgeId) match {
      case Array(jar) => ZincUtil.scalaCompiler(scalaInstance, jar)
      case _ =>
        ZincLmUtil.scalaCompiler(
          scalaInstance,
          BloopComponentsLock,
          componentProvider,
          Some(Paths.getCacheDirectory("bridge-cache").toFile),
          DependencyResolution.getEngine(userResolvers),
          bridgeSources,
          retrieveDir.toFile,
          logger
        )
    }
  }

  def getForkedJavaCompiler(javaHome: String): Option[xsbti.compile.JavaCompiler] = {
    val homeFile = new java.io.File(javaHome)
    if (homeFile.exists()) {
      import bloop.logging.DebugFilter
      logger.debug(s"Instantiating a Java compiler from $javaHome")(DebugFilter.Compilation)
      Some(new BloopForkedJavaCompiler(Some(homeFile)))
    } else {
      logger.warn(s"Ignoring non-existing Java home $javaHome, using default")
      None
    }
  }

  /**
   * A Java compiler that will invalidate class files from a forked java
   * compiler by adding a new classes directory to the begining of the
   * `-classpath` compiler option where we will write an empty class file for
   * every invalidated class file. This empty class file will force the javac
   * compiler to output an error similar to:
   *
   *  ```
   *  /path/to/File.java:1
   *    error: cannot access Bar
   *
   *  bad class file: ./B.class
   *  class file contains wrong class: java.lang.Object
   *  Please remove or make sure it appears in the correct subdirectory of the classpath.
   *  ```
   *
   * Fortunately, the last bit is not parsed by Zinc's `JavacParser` and
   * doesn't show up as a reported Zinc problem. The first bit does show up and
   * it's a good enough error to intuit that the symbol might not exist, so we
   * leave it there. We cannot replace that error with a custom one because it
   * would be confusing if the accessibility error is real and not caused by
   * our invalidation trick.
   */
  final class BloopForkedJavaCompiler(javaHome: Option[File]) extends XJavaCompiler {
    import xsbti.compile.IncToolOptions

    def run(
        sources: Array[File],
        options: Array[String],
        topts: IncToolOptions,
        reporter: XReporter,
        log: XLogger
    ): Boolean = {
      val classpathIndex = options.indexOf("-classpath")
      if (classpathIndex == -1) {
        logger.error("Missing classpath option for forked Java compiler")
        false
      } else {
        import sbt.util.InterfaceUtil
        InterfaceUtil.toOption(topts.classFileManager()) match {
          case None => logger.error("Missing class file manager for forked Java compiler"); false
          case Some(classFileManager) =>
            import java.nio.file.Files
            val newInvalidatedEntry = AbsolutePath(
              Files.createTempDirectory("invalidated-forked-javac")
            )

            val invalidatedPaths =
              classFileManager.invalidatedClassFiles().map(_.getAbsolutePath())
            val classpathValueIndex = classpathIndex + 1
            val classpathValue = options.apply(classpathValueIndex)
            val classpathEntries = classpathValue.split(File.pathSeparator)

            invalidatedPaths.foreach { invalidatedPath =>
              val relativePath = classpathEntries.collectFirst {
                case classpathEntry if invalidatedPath.startsWith(classpathEntry) =>
                  invalidatedPath.stripPrefix(classpathEntry)
              }

              relativePath.foreach { relative =>
                val relativeParts = relative.split(File.separator)
                val invalidatedClassFile = relativeParts.foldLeft(newInvalidatedEntry) {
                  case (path, relativePart) => path.resolve(relativePart)
                }

                val invalidatedClassFilePath = invalidatedClassFile.underlying
                Files.createDirectories(invalidatedClassFilePath.getParent)
                Files.write(invalidatedClassFilePath, "".getBytes())
              }
            }

            val newClasspathValue = newInvalidatedEntry.toFile + File.pathSeparator + classpathValue
            options(classpathValueIndex) = newClasspathValue

            try {
              import sbt.internal.inc.javac.BloopForkedJavaUtils
              BloopForkedJavaUtils.launch(javaHome, "javac", sources, options, log, reporter)
            } finally {
              Paths.delete(newInvalidatedEntry)
            }
        }
      }
    }
  }

  /**
   * A Java compiler that will invalidate class files using the local JDK Java
   * compiler API. The invalidated class files are invalidated programmatically
   * because they cannot be deleted from the classes directories because they
   * are read-only.
   */
  final class BloopJavaCompiler(compiler: JavaxCompiler) extends XJavaCompiler {
    import java.io.File
    import xsbti.compile.IncToolOptions
    import xsbti.Reporter
    override def run(
        sources: Array[File],
        options: Array[String],
        incToolOptions: IncToolOptions,
        reporter: Reporter,
        log0: xsbti.Logger
    ): Boolean = {
      val log: sbt.util.Logger = log0
      import collection.JavaConverters._
      val logger = new LoggerWriter(log)
      val logWriter = new PrintWriter(logger)
      log.debug("Attempting to call " + compiler + " directly...")
      val diagnostics = new DiagnosticsReporter(reporter)
      val fileManager0 = compiler.getStandardFileManager(diagnostics, null, null)

      /* Local Java compiler doesn't accept `-J<flag>` options, strip them. */
      val (invalidOptions, cleanedOptions) = options partition (_ startsWith "-J")
      if (invalidOptions.nonEmpty) {
        log.warn("Javac is running in 'local' mode. These flags have been removed:")
        log.warn(invalidOptions.mkString("\t", ", ", ""))
      }

      var compileSuccess = false
      import sbt.internal.inc.javac.WriteReportingFileManager
      val zincFileManager = incToolOptions.classFileManager().get()
      val fileManager = new BloopInvalidatingFileManager(fileManager0, zincFileManager)

      val jfiles = fileManager0.getJavaFileObjectsFromFiles(sources.toList.asJava)
      try {
        // Create directories of java args that trigger error if they don't exist
        def processJavaDirArgument(idx: Int): Unit = {
          if (idx == -1) ()
          else {
            try {
              val dir = AbsolutePath(cleanedOptions(idx + 1))
              if (dir.exists) ()
              else java.nio.file.Files.createDirectories(dir.underlying)
              ()
            } catch {
              case _: IOException => () // Ignore any error parsing path
            }
          }

        }

        processJavaDirArgument(cleanedOptions.indexOf("-d"))
        processJavaDirArgument(cleanedOptions.indexOf("-s"))
        processJavaDirArgument(cleanedOptions.indexOf("-h"))

        val newJavacOptions = cleanedOptions.toList.asJava
        val success = compiler
          .getTask(logWriter, fileManager, diagnostics, newJavacOptions, null, jfiles)
          .call()

        /* Double check success variables for the Java compiler.
         * The local compiler may report successful compilations even though
         * there have been errors (e.g. encoding problems in sources). To stick
         * to javac's behaviour, we report fail compilation from diagnostics. */
        compileSuccess = success && !diagnostics.hasErrors
      } finally {
        import sbt.util.Level
        logger.flushLines(if (compileSuccess) Level.Warn else Level.Error)
      }
      compileSuccess
    }

    final class BloopInvalidatingFileManager(
        fileManager: JavaFileManager,
        zincManager: ClassFileManager
    ) extends ForwardingJavaFileManager[JavaFileManager](fileManager) {
      import scala.collection.JavaConverters._
      override def getJavaFileForOutput(
          location: Location,
          className: String,
          kind: Kind,
          sibling: FileObject
      ): JavaFileObject = {
        val output = super.getJavaFileForOutput(location, className, kind, sibling)
        import sbt.internal.inc.javac.WriteReportingJavaFileObject
        new WriteReportingJavaFileObject(output, zincManager)
      }

      import java.{util => ju}
      override def list(
          location: Location,
          packageName: String,
          kinds: ju.Set[Kind],
          recurse: Boolean
      ): Iterable[JavaFileObject] = {
        val invalidated = zincManager.invalidatedClassFiles().map(_.getAbsolutePath()).toSet
        val ls = super.list(location, packageName, kinds, recurse)
        ls.asScala.filter { o =>
          !invalidated.exists { invalidatedPath =>
            o.getName().contains(invalidatedPath)
          }
        }.asJava
      }
    }
  }
}
