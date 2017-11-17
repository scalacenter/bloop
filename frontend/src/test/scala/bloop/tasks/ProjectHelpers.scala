package bloop
package tasks

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.Optional

import bloop.io.AbsolutePath
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

object ProjectHelpers {

  def projectDir(base: Path, name: String) = base.resolve(name)
  def sourcesDir(base: Path, name: String) = projectDir(base, name).resolve("src")
  def classesDir(base: Path, name: String) = projectDir(base, name).resolve("classes")

  def withProjects[T](projectStructures: Map[String, Map[String, String]],
                      dependencies: Map[String, Set[String]])(op: Map[String, Project] => T): T =
    withTemporaryDirectory { temp =>
      val projects = projectStructures.map {
        case (name, sources) =>
          val deps = dependencies.getOrElse(name, Set.empty)
          name -> makeProject(temp, name, sources, deps)
      }
      op(projects)
    }

  def makeProject(baseDir: Path,
                  name: String,
                  sources: Map[String, String],
                  dependencies: Set[String]): Project = {
    val (srcs, classes) = makeProjectStructure(baseDir, name)
    val tempDir = projectDir(baseDir, name).resolve("tmp")
    Files.createDirectories(tempDir)

    val target = classesDir(baseDir, name)
    val classpath = dependencies.map(classesDir(baseDir, _)) + target
    val previousResult =
      PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])
    writeSources(srcs, sources)
    Project(
      name = name,
      dependencies = dependencies.toArray,
      scalaInstance = CompilationHelpers.scalaInstance,
      classpath = classpath.toArray.map(AbsolutePath.apply),
      classesDir = AbsolutePath(target),
      scalacOptions = Array.empty,
      javacOptions = Array.empty,
      sourceDirectories = Array(AbsolutePath(srcs)),
      previousResult = previousResult,
      tmp = AbsolutePath(tempDir),
      origin = None
    )
  }

  def makeProjectStructure[T](base: Path, name: String): (Path, Path) = {
    val srcs = sourcesDir(base, name)
    val classes = classesDir(base, name)
    Files.createDirectories(srcs)
    Files.createDirectories(classes)
    (srcs, classes)
  }

  def writeSources[T](srcDir: Path, sources: Map[String, String]): Unit = {
    sources.foreach {
      case (name, contents) =>
        val writer = Files.newBufferedWriter(srcDir.resolve(name), Charset.forName("UTF-8"))
        try writer.write(contents)
        finally writer.close()
    }
  }

  def withTemporaryDirectory[T](op: Path => T): T = {
    val temp = Files.createTempDirectory("tmp-test")
    try op(temp)
    finally delete(temp)
  }

  def delete(path: Path): Unit = {
    Files.walkFileTree(
      path,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      }
    )
    ()
  }
}
