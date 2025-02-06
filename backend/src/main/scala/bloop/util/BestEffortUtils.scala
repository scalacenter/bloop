package bloop.util

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

import scala.collection.JavaConverters._

import bloop.io.AbsolutePath

object BestEffortUtils {

  case class BestEffortProducts(
      compileProducts: bloop.CompileProducts,
      hash: BestEffortHash,
      recompile: Boolean
  )

  sealed trait BestEffortHash
  case class NonEmptyBestEffortHash(inputHash: String, outputHash: String) extends BestEffortHash
  case object EmptyBestEffortHash extends BestEffortHash

  def hashInput(
      outputDir: Path,
      sources: Array[AbsolutePath],
      classpath: Array[AbsolutePath],
      ignoredClasspathDirectories: List[AbsolutePath]
  ): String = {
    val md = MessageDigest.getInstance("SHA-1")

    md.update("<inputs>".getBytes())
    sources.foreach { sourceFilePath =>
      val underlying = sourceFilePath.underlying
      if (Files.exists(underlying) && Files.isRegularFile(underlying)) {
        md.update(Files.readAllBytes(underlying))
      }
    }
    md.update("<classpath>".getBytes())
    classpath.map(_.underlying).foreach { classpathFile =>
      if (
        !Files.exists(classpathFile)
        || ignoredClasspathDirectories.exists(_.underlying == classpathFile)
        || outputDir == classpathFile
      ) ()
      else if (Files.isRegularFile(classpathFile)) {
        md.update(Files.readAllBytes(classpathFile))
      } else if (Files.isDirectory(classpathFile)) {
        Files.walk(classpathFile).iterator().asScala.foreach { file =>
          if (Files.isRegularFile(file)) {
            md.update(Files.readAllBytes(file))
          }
        }
      }
    }

    val digest = new BigInteger(1, md.digest())
    String.format(s"%040x", digest)
  }

  def hashOutput(
      outputDir: Path
  ): String = {
    val md = MessageDigest.getInstance("SHA-1")

    md.update("<outputs>".getBytes())
    if (Files.exists(outputDir)) {
      Files.walk(outputDir).iterator().asScala.foreach { path =>
        if (Files.isRegularFile(path)) {
          md.update(path.toString.getBytes())
          md.update(Files.readAllBytes(path))
        }
      }
    }

    val digest = new BigInteger(1, md.digest())
    String.format(s"%040x", digest)
  }

  /* Hashes results of a projects compilation, to mimic how it would have been handled in zinc.
   * Returns a pair of SHA-1 of an input and output of a project.
   *
   * Since currently for best-effort compilation we are unable to use neither incremental compilation,
   * nor the data supplied by zinc (like the compilation analysis files, which are not able to be generated
   * since the compiler is able to skip the necessary phases for now), this custom implementation
   * is meant to keep the best-effort projects from being unnecessarily recompiled.
   *
   */
  def hashAll(
      outputDir: Path,
      sources: Array[AbsolutePath],
      classpath: Array[AbsolutePath],
      ignoredClasspathDirectories: List[AbsolutePath]
  ) =
    NonEmptyBestEffortHash(
      hashInput(outputDir, sources, classpath, ignoredClasspathDirectories),
      hashOutput(outputDir)
    )

}
