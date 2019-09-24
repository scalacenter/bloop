package bloop

import java.io.File
import java.{util => ju}

import sbt.util.InterfaceUtil
import sbt.internal.inc.classpath.ClasspathUtilities

import xsbti.compile.PreviousResult
import xsbti.compile.PerClasspathEntryLookup
import xsbti.compile.CompileAnalysis
import xsbti.compile.DefinesClass
import java.util.zip.ZipFile
import java.util.zip.ZipException
import java.util.concurrent.ConcurrentHashMap
import xsbti.compile.FileHash
import sbt.internal.inc.bloop.internal.BloopNameHashing
import sbt.internal.inc.bloop.internal.BloopStamps

final class BloopClasspathEntryLookup(
    results: Map[File, PreviousResult],
    classpathHashes: Vector[FileHash]
) extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: File): ju.Optional[CompileAnalysis] = {
    InterfaceUtil.toOptional(results.get(classpathEntry)).flatMap(_.analysis())
  }

  override def definesClass(entry: File): DefinesClass = {
    if (!entry.exists) FalseDefinesClass
    else {
      classpathHashes.find(fh => fh.file() == entry) match {
        case None => FalseDefinesClass
        case Some(entryHash) =>
          def computeDefinesClassForJar = {
            if (!ClasspathUtilities.isArchive(entry, contentFallback = true)) FalseDefinesClass
            else new JarDefinesClass(entry)
          }

          if (BloopStamps.isDirectoryHash(entryHash)) new DirectoryDefinesClass(entry)
          else {
            val (_, cachedDefinesClass) = BloopClasspathEntryLookup.definedClasses.compute(
              entry,
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
      val jar = try {
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
}
