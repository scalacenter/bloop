package bloop.io

import bloop.logging.RecordingLogger
import scala.concurrent.Promise
import bloop.util.TestUtil
import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import bloop.util.TestProject
import java.util.concurrent.TimeUnit
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.data.SourcesGlobs

object SourcesGlobsSpec extends bloop.testing.BaseSuite {

  def checkGlobs(
      name: String,
      filenames: List[String],
      includeGlobs: List[String],
      excludeGlobs: List[String],
      expectedFilenames: String
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit =
    test(name) {
      TestUtil.withinWorkspace { workspace =>
        import bloop.engine.ExecutionContext.ioScheduler
        val testProject = TestProject(
          workspace,
          "globs",
          filenames.map(name => s"/$name${System.lineSeparator()}// command")
        )
        val logger = new RecordingLogger(ansiCodesSupported = false)
        val configDir = TestProject.populateWorkspace(workspace, List(testProject))
        val state = TestUtil.loadTestProject(configDir.underlying, logger)
        val baseProject = state.build.loadedProjects.head.project
        val globDirectory = baseProject.baseDirectory.resolve("src")
        val sourcesGlobs = SourcesGlobs.fromStrings(
          name,
          globDirectory,
          None,
          if (includeGlobs.isEmpty) List("glob:**")
          else includeGlobs,
          excludeGlobs,
          logger
        )
        val project = baseProject.copy(
          sources = Nil,
          sourcesGlobs = sourcesGlobs
        )
        val hashedSources = SourceHasher.findAndHashSourcesInProject(
          project,
          1,
          Promise[Unit](),
          ioScheduler
        )
        val Right(result) = TestUtil.await(10, TimeUnit.SECONDS)(hashedSources)
        import scala.collection.JavaConverters._
        val obtainedFilenames = result
          .map(
            _.source
              .toRelative(globDirectory)
              .underlying
              .iterator()
              .asScala
              .mkString("/")
              .stripPrefix("globs/src/")
          )
          .sorted
          .mkString("\n")
        assertNoDiff(obtainedFilenames, expectedFilenames)
      }
    }

  checkGlobs(
    "include",
    List("Foo.scala", "FooTest.scala"),
    List("glob:*Test.scala"),
    List(),
    """|FooTest.scala
       |""".stripMargin
  )

  checkGlobs(
    "exclude",
    List("Foo.scala", "FooTest.scala"),
    List(),
    List("glob:*Test.scala"),
    """|Foo.scala
       |""".stripMargin
  )

  checkGlobs(
    "recursive-include",
    List("main/scala/Foo.scala", "main/scala/FooTest.scala"),
    List("glob:**/Foo.scala"),
    List(),
    """|main/scala/Foo.scala
       |""".stripMargin
  )

  checkGlobs(
    "double-asterisk-slash",
    List("Foo.scala", "inner/Bar.scala"),
    List("glob:**/*.scala"),
    List(),
    // NOTE(olafur): globs in Bloop follow `java.nio.file.PathMatcher`
    // semantics, which may be incompatible with how other builds tools
    // interpret file globs. For example, "Foo.scala" matches the glob "**/*" in
    // Pants while it doesn't with Bloop. Client tools are responsible for
    // pre-processing globs so that they match `PathMatcher` semantics.
    """|inner/Bar.scala
       |""".stripMargin
  )

  checkGlobs(
    "double-asterisk-no-slash",
    List("Foo.scala", "inner/Bar.scala"),
    List("glob:**.scala"),
    List(),
    """|Foo.scala
       |inner/Bar.scala
       |""".stripMargin
  )

  checkGlobs(
    "recursive-exclude",
    List("main/scala/Foo.scala", "main/scala/FooTest.scala"),
    List(),
    List("glob:**/Foo.scala"),
    """|main/scala/FooTest.scala
       |""".stripMargin
  )

  checkGlobs(
    "recursive-exclude",
    List("main/scala/Foo.scala", "main/scala/FooTest.scala"),
    List(),
    List("glob:**/Foo.scala"),
    """|main/scala/FooTest.scala
       |""".stripMargin
  )

  test("invalid-glob") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val globs = SourcesGlobs.fromStrings(
      "error",
      AbsolutePath.workingDirectory,
      None,
      List("*.scala"), // missing 'glob:' prefix
      List(),
      logger
    )
    assertEquals(globs, Nil)
    val obtained = logger
      .renderErrors()
      .replaceAllLiterally(AbsolutePath.workingDirectory.toString(), "PATH")
    assertNoDiff(
      obtained,
      "ignoring invalid 'sourcesGlobs' object containing directory 'PATH' in project 'error'"
    )
  }

}
