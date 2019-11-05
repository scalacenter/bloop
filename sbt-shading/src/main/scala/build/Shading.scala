package build

import java.io.{File, FileInputStream}
import java.util.jar.JarInputStream
import java.util.zip.{ZipEntry, ZipInputStream}

/*
import com.tonicsystems.jarjar.classpath.ClassPath
import com.tonicsystems.jarjar.transform.JarTransformer
import com.tonicsystems.jarjar.transform.config.ClassRename
import com.tonicsystems.jarjar.transform.jar.DefaultJarProcessor
 */

import _root_.org.pantsbuild.jarjar._
import _root_.org.pantsbuild.jarjar.util._

import sbt.file

object Shading {
  def zipEntries(zipStream: ZipInputStream): Iterator[ZipEntry] =
    new Iterator[ZipEntry] {
      var nextEntry = Option.empty[ZipEntry]
      def update() =
        nextEntry = Option(zipStream.getNextEntry)

      update()

      def hasNext = nextEntry.nonEmpty
      def next() = {
        val ent = nextEntry.get
        update()
        ent
      }
    }

  def jarClassNames(jar: File): Seq[String] = {

    var fis: FileInputStream = null
    var zis: JarInputStream = null

    try {
      fis = new FileInputStream(jar)
      zis = new JarInputStream(fis)

      zipEntries(zis)
        .map(_.getName)
        .filter(_.endsWith(".class"))
        .map(_.stripSuffix(".class").replace('/', '.'))
        .toVector
    } finally {
      if (zis != null)
        zis.close()
      if (fis != null)
        fis.close()
    }
  }

  def toShadeClasses(
      shadeNamespaces: Set[String],
      toShadeJars: Seq[File],
      log: sbt.Logger,
      verbose: Boolean
  ): Seq[String] = {
    def infoIfVerbose(msg: String) = {
      if (verbose) log.info(msg)
      else log.debug(msg)
    }

    infoIfVerbose(
      s"Shading ${toShadeJars.length} JAR(s):\n" +
        toShadeJars.map("  " + _).sorted.mkString("\n")
    )

    val toShadeClasses0 = toShadeJars.flatMap(jarClassNames)

    log.info(s"Found ${toShadeClasses0.length} class(es) in JAR(s) to be shaded")
    log.debug(toShadeClasses0.map("  " + _).sorted.mkString("\n"))

    val toShadeClasses = shadeNamespaces.toVector.sorted.foldLeft(toShadeClasses0) {
      (toShade, namespace) =>
        val prefix = namespace + "."
        val (filteredOut, remaining) = toShade.partition(_.startsWith(prefix))

        infoIfVerbose(
          s"${filteredOut.length} classes already filtered out by shaded namespace $namespace"
        )
        log.debug(filteredOut.map("  " + _).sorted.mkString("\n"))

        remaining
    }

    if (shadeNamespaces.nonEmpty) {
      log.info(s"${toShadeClasses.length} remaining class(es) to be shaded")
      log.debug(toShadeClasses.map("  " + _).sorted.mkString("\n"))
    }

    toShadeClasses
  }

  def createPackage(
      baseJar: File,
      unshadedJars: Seq[File],
      shadingNamespace: String,
      shadeNamespaces: Set[String],
      shadeIgnoredNamespaces: Set[String],
      toShadeClasses: Seq[String],
      toShadeJars: Seq[File]
  ) = {

    val outputJar = new File(
      baseJar.getParentFile,
      baseJar.getName.stripSuffix(".jar") + "-shading.jar"
    )

    def rename(from: String, to: String): Rule = {
      val rule = new Rule
      rule.setPattern(from)
      rule.setResult(to)
      rule
    }

    def zap(namespace: String): Zap = {
      val rule = new Zap
      rule.setPattern(namespace)
      rule
    }

    val ignoredRules = shadeIgnoredNamespaces.toVector.map(zap(_))

    val nsRules = shadeNamespaces.toVector.sorted.map { namespace =>
      rename(namespace + ".**", shadingNamespace + ".@0")
    }
    val clsRules = toShadeClasses.map { cls =>
      rename(cls, shadingNamespace + ".@0")
    }

    import scala.collection.JavaConverters._
    val allRules = ignoredRules ++ nsRules ++ clsRules
    val processor = JarJarProcessor(ignoredRules, verbose = false, skipManifest = false)
    CoursierJarProcessor.run(
      ((baseJar +: unshadedJars) ++ toShadeJars).toArray,
      outputJar,
      processor.proc,
      true
    )

    outputJar
  }
}
