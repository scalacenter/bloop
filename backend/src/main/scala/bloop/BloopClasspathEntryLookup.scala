package bloop

import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.{util => ju}

import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.util.InterfaceUtil
import xsbti.FileConverter
import xsbti.VirtualFile
import xsbti.compile.CompileAnalysis
import xsbti.compile.DefinesClass
import xsbti.compile.FileHash
import xsbti.compile.PerClasspathEntryLookup
import xsbti.compile.PreviousResult

final class BloopClasspathEntryLookup(
    results: Map[File, PreviousResult],
    classpathHashes: Vector[FileHash],
    converter: FileConverter
) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: VirtualFile): ju.Optional[CompileAnalysis] = {
    val file = converter.toPath(classpathEntry).toFile()
    InterfaceUtil.toOptional(results.get(file)).flatMap(_.analysis())
  }

  override def definesClass(entry: VirtualFile): DefinesClass = {
    val path = converter.toPath(entry)
    val file = path.toFile()
    if (!file.exists) FalseDefinesClass
    else {
      classpathHashes.find(fh => fh.file() == file) match {
        case None => FalseDefinesClass
        case Some(entryHash) =>
          def computeDefinesClassForJar = {
            if (!ClasspathUtil.isArchive(path, contentFallback = true)) FalseDefinesClass
            else new JarDefinesClass(file)
          }

          if (BloopStamps.isDirectoryHash(entryHash)) new DirectoryDefinesClass(file)
          else {
            val (_, cachedDefinesClass) = BloopClasspathEntryLookup.definedClasses.compute(
              file,
              (entry, definesClass) => {
                definesClass match {
                  case null =>
                    if (entry.isDirectory()) entryHash -> new DirectoryDefinesClass(entry)
                    else entryHash -> computeDefinesClassForJar
                  case current @ (cachedHash, cachedDefinesClass) =>
                    if (entryHash.hash() == cachedHash.hash()) current
                    else entryHash -> computeDefinesClassForJar
                }
              }
            )

            cachedDefinesClass
          }
      }
    }
  }

  private object FalseDefinesClass extends DefinesClass {
    override def apply(binaryClassName: String): Boolean = false
  }

  private val ClassExt = ".class"
  private class JarDefinesClass(entry: File) extends DefinesClass {
    private def toClassName(entry: String): String =
      entry.stripSuffix(ClassExt).replace('/', '.')
    private lazy val entries: Set[String] = {
      val jar =
        try {
          new ZipFile(entry, ZipFile.OPEN_READ)
        } catch {
          case e: ZipException =>
            // ZipException doesn't include the file name :(
            throw new RuntimeException("Error opening zip file: " + entry.getName, e)
        }

      try {
        import collection.JavaConverters._
        jar.entries.asScala.map(e => toClassName(e.getName)).toSet
      } finally {
        jar.close()
      }
    }

    override def apply(binaryClassName: String): Boolean =
      entries.contains(binaryClassName)
  }

  private class DirectoryDefinesClass(entry: File) extends DefinesClass {
    override def apply(binaryClassName: String): Boolean = {
      classFile(entry, binaryClassName).isFile
    }

    private def classFile(baseDir: File, className: String): File = {
      val (pkg, name) = components(className)
      val dir = subDirectory(baseDir, pkg)
      new File(dir, name + ClassExt)
    }

    private def subDirectory(base: File, parts: Seq[String]): File =
      (base /: parts)((b, p) => new File(b, p))

    private def components(className: String): (Seq[String], String) = {
      assume(!className.isEmpty)
      val parts = className.split("\\.")
      if (parts.length == 1) (Nil, parts(0)) else (parts.init, parts.last)
    }
  }
}

object BloopClasspathEntryLookup {
  private[bloop] final val definedClasses = new ConcurrentHashMap[File, (FileHash, DefinesClass)]()

  def definedClassFileInDependencies(
      relativeClassFile: String,
      results: Map[File, PreviousResult]
  ): Option[Path] = {
    def findClassFile(t: (File, PreviousResult)): Option[Path] = {
      val (classesDir, result) = t
      val targetFile = classesDir.toPath().resolve(relativeClassFile)
      val targetClassFile = PlainVirtualFileConverter.converter.toVirtualFile(targetFile)
      InterfaceUtil.toOption(result.analysis()).flatMap { analysis0 =>
        val analysis = analysis0.asInstanceOf[sbt.internal.inc.Analysis]
        val definedClass = analysis.relations.allProducts.contains(targetClassFile)
        if (definedClass) Some(targetFile) else None
      }
    }

    results.collectFirst(Function.unlift(findClassFile))
  }
}
