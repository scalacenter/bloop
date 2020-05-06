package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import bloop.engine.{Build, BuildLoader, State}
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.{Logger, RecordingLogger}
import bloop.util.TestUtil
import bloop.testing.BaseSuite
import bloop.data.WorkspaceSettings
import bloop.internal.build.BuildInfo
import bloop.tracing.TraceProperties
import monix.eval.Task
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import scala.util.Try
import bloop.data.TraceSettings

object BuildLoaderSpec extends BaseSuite {
  testLoad("don't reload if nothing changes") { (testBuild, logger) =>
    testBuild.state.build.checkForChange(None, logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got ${action}")
    }
  }

  testLoad("reload if settings are added") { (testBuild, logger) =>
    val settings = WorkspaceSettings.fromSemanticdbSettings("4.2.0", List(BuildInfo.scalaVersion))
    testBuild.state.build.checkForChange(Some(settings), logger).map {
      case Build.ReturnPreviousState =>
        sys.error(s"Expected return updated state, got previous state")
      case action: Build.UpdateState =>
        assert(action.createdOrModified.size == 0)
        assert(action.deleted.size == 0)
        assert(action.invalidated.size == 4)
        assert(action.settingsForReload == Some(settings))
    }
  }

  val sameSettings = WorkspaceSettings.fromSemanticdbSettings("4.2.0", List(BuildInfo.scalaVersion))
  testLoad("do not reload if same settings are added", Some(sameSettings)) { (testBuild, logger) =>
    testBuild.state.build.checkForChange(Some(sameSettings), logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState =>
        sys.error(s"Expected return previous state, got updated state")
    }
  }

  testLoad("reload if new settings are added", Some(sameSettings)) { (testBuild, logger) =>
    val newSettings =
      WorkspaceSettings.fromSemanticdbSettings("4.1.11", List(BuildInfo.scalaVersion))
    testBuild.state.build.checkForChange(Some(newSettings), logger).map {
      case Build.ReturnPreviousState =>
        sys.error(s"Expected return updated state, got previous state")
      case action: Build.UpdateState =>
        assert(action.createdOrModified.size == 0)
        assert(action.deleted.size == 0)
        assert(action.invalidated.size == 4)
        assert(action.settingsForReload == Some(newSettings))
    }
  }

  private def changeHashOfRandomFiles(build: TestBuild, n: Int): Unit = {
    val randomFiles = scala.util.Random.shuffle(configurationFiles(build)).take(n)
    randomFiles.foreach { f =>
      // Add whitespace at the end to modify hash
      val bytes = Files.readAllBytes(f.underlying)
      val contents = new String(bytes, StandardCharsets.UTF_8) + " "
      Files.write(f.underlying, contents.getBytes(StandardCharsets.UTF_8))
    }
  }

  testLoad("reload if two file contents changed in build with previous", Some(sameSettings)) {
    (testBuild, logger) =>
      changeHashOfRandomFiles(testBuild, 2)
      // Don't pass in any settings so that the previous ones are used instead
      testBuild.state.build.checkForChange(None, logger).map {
        case Build.ReturnPreviousState =>
          sys.error(s"Expected return updated state, got previous state")
        case action: Build.UpdateState =>
          assert(action.createdOrModified.size == 2)
          assert(action.deleted.size == 0)
          assert(action.invalidated.size == 0)
          assert(action.settingsForReload == Some(sameSettings))
      }
  }

  testLoad("reload if two file contents changed with same settings", Some(sameSettings)) {
    (testBuild, logger) =>
      changeHashOfRandomFiles(testBuild, 2)
      testBuild.state.build.checkForChange(Some(sameSettings), logger).map {
        case Build.ReturnPreviousState =>
          sys.error(s"Expected return updated state, got previous state")
        case action: Build.UpdateState =>
          assert(action.createdOrModified.size == 2)
          assert(action.deleted.size == 0)
          assert(action.invalidated.size == 0)
          assert(action.settingsForReload == Some(sameSettings))
      }
  }

  testLoad("reload if new settings are added and two file contents changed", Some(sameSettings)) {
    (testBuild, logger) =>
      changeHashOfRandomFiles(testBuild, 2)
      val newSettings =
        WorkspaceSettings.fromSemanticdbSettings("4.1.11", List(BuildInfo.scalaVersion))
      testBuild.state.build.checkForChange(Some(newSettings), logger).map {
        case Build.ReturnPreviousState =>
          sys.error(s"Expected return updated state, got previous state")
        case action: Build.UpdateState =>
          assert(action.deleted.size == 0)
          assert(action.createdOrModified.size == 2)
          assert(action.invalidated.size == 2)
          assert(action.settingsForReload == Some(newSettings))
      }
  }

  testLoad(
    "do not reload if no settings are passed to build configured with previous settings",
    Some(sameSettings)
  ) { (testBuild, logger) =>
    testBuild.state.build.checkForChange(None, logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState =>
        sys.error(s"Expected return previous state, got updated state")
    }
  }

  testLoad("do not reload on empty settings") { (testBuild, logger) =>
    val configDir = testBuild.configFileFor(testBuild.projects.head).getParent
    testBuild.state.build.checkForChange(None, logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got $action")
    }
  }

  private def configurationFiles(build: TestBuild): List[AbsolutePath] = {
    build.projects.map(p => build.configFileFor(p))
  }

  testLoad("don't reload when configuration files are touched") { (testBuild, logger) =>
    val randomConfigFiles = scala.util.Random.shuffle(configurationFiles(testBuild)).take(5)
    // Update the timestamps of the configuration files to trigger a reload
    randomConfigFiles.foreach(f => Files.write(f.underlying, Files.readAllBytes(f.underlying)))
    testBuild.state.build.checkForChange(None, logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got ${action}")
    }
  }

  // We add a new project with the bare minimum information
  private val ContentsNewConfigurationFile: String = {
    """
      |{
      |    "version" : "1.0.0",
      |    "project" : {
      |        "name" : "dummy",
      |        "directory" : "/tmp/dummy",
      |        "sources" : [],
      |        "dependencies" : [],
      |        "classpath" : [],
      |        "out" : "/tmp/dummy/target",
      |        "classesDir" : "/tmp/dummy/target/classes"
      |    }
      |}""".stripMargin
  }

  testLoad("reload when new configuration file is added to the build") { (testBuild, logger) =>
    val pathOfDummyFile = testBuild.state.build.origin.resolve("dummy.json").underlying
    Files.write(pathOfDummyFile, ContentsNewConfigurationFile.getBytes(StandardCharsets.UTF_8))

    testBuild.state.build.checkForChange(None, logger).map {
      case action: Build.UpdateState =>
        val hasDummyPath =
          action.createdOrModified.exists(_.origin.path.underlying == pathOfDummyFile)
        if (action.deleted.isEmpty && hasDummyPath) ()
        else sys.error(s"Expected state with new project addition, got ${action}")
      case Build.ReturnPreviousState =>
        sys.error(s"Expected state with new project addition, got ReturnPreviousState")
    }
  }

  testLoad("reload when existing configuration files change") { (testBuild, logger) =>
    val projectsToModify = testBuild.state.build.loadedProjects.map(_.project).take(2)
    val backups = projectsToModify.map(p => p -> p.origin.path.readAllBytes)

    val changes = backups.map {
      case (p, bytes) =>
        Task {
          val newContents = (new String(bytes)).replace(p.name, s"${p.name}z")
          Files.write(p.origin.path.underlying, newContents.getBytes(StandardCharsets.UTF_8))
        }
    }

    Task
      .gatherUnordered(changes)
      .flatMap { _ =>
        testBuild.state.build.checkForChange(None, logger).map {
          case action: Build.UpdateState =>
            val hasAllProjects = {
              val originProjects = projectsToModify.map(_.origin.path).toSet
              action.createdOrModified.map(_.origin.path).toSet == originProjects
            }

            if (action.deleted.isEmpty && hasAllProjects) ()
            else sys.error(s"Expected state modifying ${projectsToModify}, got ${action}")
          case Build.ReturnPreviousState =>
            sys.error(s"Expected state modifying ${projectsToModify}, got ReturnPreviousState")
        }
      }
  }

  testLoad("reload when new configuration file is deleted") { (testBuild, logger) =>
    val configurationFile = testBuild.configFileFor(testBuild.projects.head)
    val change = Task {
      Files.delete(configurationFile.underlying)
    }

    change.flatMap { _ =>
      testBuild.state.build.checkForChange(None, logger).map {
        case action: Build.UpdateState =>
          val hasProjectDeleted = {
            action.deleted match {
              case List(p) if p == configurationFile => true
              case _ => false
            }
          }

          if (action.createdOrModified.isEmpty && hasProjectDeleted) ()
          else sys.error(s"Expected state with deletion of ${configurationFile}, got ${action}")
        case Build.ReturnPreviousState =>
          sys.error(
            s"Expected state with deletion of ${configurationFile}, got ReturnPreviousState"
          )
      }
    }
  }

  test("print helpful error when project json configuration file can't be parsed") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val state = loadState(workspace, Nil, logger, None)
      val unparseableJsonFile = state.build.origin.resolve("unparseable.json")
      Files.write(unparseableJsonFile.underlying, "{}".getBytes(StandardCharsets.UTF_8))
      val failedState = Try(TestUtil.loadTestProject(state.build.origin.underlying, logger))
      assert(failedState.isFailure)
      assert(failedState.failed.get.getMessage.contains("Failed to load project from"))
    }
  }

  test("build respects different trace properties in separate workspaces") {
    TestUtil.withinWorkspace { workspace1 =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val settings1 = WorkspaceSettings(
        None,
        None,
        None,
        Some(
          TraceSettings(
            serverUrl = Some("http://127.0.0.2"),
            debugTracing = Some(false),
            verbose = Some(false),
            localServiceName = Some("42"),
            traceStartAnnotation = Some("start"),
            traceEndAnnotation = Some("end")
          )
        )
      )

      val state1 = loadState(workspace1, Nil, logger, Some(settings1))
      TestUtil.withinWorkspace { workspace2 =>
        val settings2 = settings1.copy(
          traceSettings = Some(
            TraceSettings.fromProperties(TraceProperties.default)
          )
        )

        val state2 = loadState(workspace2, Nil, logger, Some(settings2))
        assert(state1.build.workspaceSettings.isDefined)
        assert(state2.build.workspaceSettings.isDefined)
        assert(state1.build.workspaceSettings.get != state2.build.workspaceSettings.get)
      }
    }
  }

  def testLoad[T](name: String, settings: Option[WorkspaceSettings] = None)(
      fun: (TestBuild, RecordingLogger) => Task[T]
  ): Unit = {
    test(name) {
      loadBuildState(fun, settings)
    }
  }

  def loadBuildState[T](
      f: (TestBuild, RecordingLogger) => Task[T],
      settings: Option[WorkspaceSettings] = None
  ): T = {
    TestUtil.withinWorkspace { workspace =>
      import bloop.util.TestProject
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val a = TestProject(workspace, "a", Nil)
      val b = TestProject(workspace, "b", Nil)
      val c = TestProject(workspace, "c", Nil)
      val d = TestProject(workspace, "d", Nil)
      val projects = List(a, b, c, d)
      val state = loadState(workspace, projects, logger, settings)
      val configDir = state.build.origin
      val build = TestBuild(state, projects)
      TestUtil.blockOnTask(f(build, logger), 5)
    }
  }
}
