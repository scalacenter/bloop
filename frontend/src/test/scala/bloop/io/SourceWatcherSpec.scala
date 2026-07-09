package bloop.io

import java.nio.file.Path

import bloop.data.SourcesGlobs
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

object SourceWatcherSpec extends bloop.testing.BaseSuite {

  private val logger = new RecordingLogger(ansiCodesSupported = false)

  private def globs(
      directory: AbsolutePath,
      includes: List[String],
      excludes: List[String],
      walkDepth: Option[Int] = None
  ): List[SourcesGlobs] =
    SourcesGlobs.fromStrings("test", directory, walkDepth, includes, excludes, logger)

  private def isWatched(
      plainSources: Seq[Path],
      projectGlobs: Seq[SourcesGlobs],
      generatorGlobs: Seq[SourcesGlobs],
      path: AbsolutePath
  ): Boolean =
    SourceWatcher.isWatchedSourceFile(plainSources, projectGlobs, generatorGlobs, path.underlying)

  test("plain source files keep the default non-hidden source-file rule") {
    TestUtil.withinWorkspace { workspace =>
      val srcDir = workspace.resolve("src")
      val plain = List(srcDir.underlying)
      assert(isWatched(plain, Nil, Nil, srcDir.resolve("Foo.scala")))
      assert(isWatched(plain, Nil, Nil, srcDir.resolve("nested").resolve("Bar.java")))
      // hidden and non-source files are never watched
      assert(!isWatched(plain, Nil, Nil, srcDir.resolve(".Hidden.scala")))
      assert(!isWatched(plain, Nil, Nil, srcDir.resolve("notes.txt")))
    }
  }

  test("files in a sources-glob directory follow the glob includes and excludes") {
    TestUtil.withinWorkspace { workspace =>
      val globDir = workspace.resolve("globbed")
      val projectGlobs = globs(globDir, List("glob:*.scala"), List("glob:*Excluded.scala"))
      assert(isWatched(Nil, projectGlobs, Nil, globDir.resolve("Foo.scala")))
      // a glob-excluded file must not wake compilation (the fix)
      assert(!isWatched(Nil, projectGlobs, Nil, globDir.resolve("FooExcluded.scala")))
      // an extension the glob does not include is ignored even though it is a source file
      assert(!isWatched(Nil, projectGlobs, Nil, globDir.resolve("Legacy.java")))
    }
  }

  test("a plain source directory rescues a file excluded by an overlapping glob") {
    TestUtil.withinWorkspace { workspace =>
      val sharedDir = workspace.resolve("shared")
      val projectGlobs = globs(sharedDir, List("glob:*.scala"), List("glob:Excluded.scala"))
      val plain = List(sharedDir.underlying)
      // the glob excludes it, but another project compiles the directory as a plain source
      assert(isWatched(plain, projectGlobs, Nil, sharedDir.resolve("Excluded.scala")))
    }
  }

  test("a broad glob does not match files outside its own directory") {
    TestUtil.withinWorkspace { workspace =>
      val dirA = workspace.resolve("a")
      val dirB = workspace.resolve("b")
      // a recursive glob in one project must not wake compilation for files that live in another
      // project's directory and are excluded there
      val projectGlobs =
        globs(dirA, List("glob:**/*.scala"), Nil) ++
          globs(dirB, List("glob:*.scala"), List("glob:*Excluded.scala"))
      assert(isWatched(Nil, projectGlobs, Nil, dirB.resolve("Foo.scala")))
      assert(!isWatched(Nil, projectGlobs, Nil, dirB.resolve("FooExcluded.scala")))
    }
  }

  test("source globs obey walkDepth when matching watcher events") {
    TestUtil.withinWorkspace { workspace =>
      val globDir = workspace.resolve("globbed")
      val projectGlobs = globs(globDir, List("glob:**.scala"), Nil, Some(1))
      assert(isWatched(Nil, projectGlobs, Nil, globDir.resolve("Foo.scala")))
      assert(!isWatched(Nil, projectGlobs, Nil, globDir.resolve("nested").resolve("Foo.scala")))
    }
  }

  test("source generator inputs are watched even outside glob and plain directories") {
    TestUtil.withinWorkspace { workspace =>
      val inputsDir = workspace.resolve("generator-inputs")
      val generatorGlobs = globs(inputsDir, List("glob:*.in"), Nil)
      assert(isWatched(Nil, Nil, generatorGlobs, inputsDir.resolve("data.in")))
      // a stray source file in a generator input directory keeps the default rule
      assert(isWatched(Nil, Nil, generatorGlobs, inputsDir.resolve("Stray.scala")))
    }
  }

  test("source generator globs obey walkDepth when matching watcher events") {
    TestUtil.withinWorkspace { workspace =>
      val inputsDir = workspace.resolve("generator-inputs")
      val generatorGlobs = globs(inputsDir, List("glob:**.in"), Nil, Some(1))
      assert(isWatched(Nil, Nil, generatorGlobs, inputsDir.resolve("data.in")))
      assert(!isWatched(Nil, Nil, generatorGlobs, inputsDir.resolve("sub").resolve("data.in")))
    }
  }

  test("a broad generator glob does not match files outside its own directory") {
    TestUtil.withinWorkspace { workspace =>
      val inputsDir = workspace.resolve("generator-inputs")
      val otherDir = workspace.resolve("other")
      val generatorGlobs = globs(inputsDir, List("glob:**/*.in"), Nil)
      assert(isWatched(Nil, Nil, generatorGlobs, inputsDir.resolve("sub").resolve("data.in")))
      // a like-named file in an unrelated directory must not be claimed by the generator glob
      assert(!isWatched(Nil, Nil, generatorGlobs, otherDir.resolve("data.in")))
    }
  }
}
