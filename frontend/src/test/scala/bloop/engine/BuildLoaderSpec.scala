package bloop.engine

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.data.Project
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.{Logger, RecordingLogger}
import bloop.tasks.TestUtil
import monix.eval.Task
import org.junit.Test

class BuildLoaderSpec {
  @Test
  def noReloadWhenNothingChanges(): Unit = {
    val logger = new RecordingLogger()
    val BuildLoaderState(state, configurationFiles) = loadBuildState(logger)
    val checkTask = state.build.checkForChange(logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got ${action}")
    }

    TestUtil.blockOnTask(checkTask, 5)
  }

  @Test
  def noReloadWhenConfigurationFilesAreTouched(): Unit = {
    val logger = new RecordingLogger()
    val BuildLoaderState(state, configurationFiles) = loadBuildState(logger)
    val randomConfigFiles = scala.util.Random.shuffle(configurationFiles).take(5)
    // Update the timestamps of the configuration files to trigger a reload
    randomConfigFiles.foreach(f => Files.write(f.underlying, Files.readAllBytes(f.underlying)))

    val checkTask = state.build.checkForChange(logger).map {
      case Build.ReturnPreviousState => ()
      case action: Build.UpdateState => sys.error(s"Expected return previous state, got ${action}")
    }

    TestUtil.blockOnTask(checkTask, 5)
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

  @Test
  def reloadWhenNewConfigurationFile(): Unit = {
    val logger = new RecordingLogger()
    val BuildLoaderState(state, configurationFiles) = loadBuildState(logger)
    val pathOfDummyFile = state.build.origin.resolve("dummy.json").underlying
    Files.write(pathOfDummyFile, ContentsNewConfigurationFile.getBytes(StandardCharsets.UTF_8))

    val checkTask = state.build
      .checkForChange(logger)
      .map {
        case action: Build.UpdateState =>
          val hasDummyPath =
            action.createdOrModified.exists(_.origin.path.underlying == pathOfDummyFile)
          if (action.deleted.isEmpty && hasDummyPath) ()
          else sys.error(s"Expected state with new project addition, got ${action}")
        case Build.ReturnPreviousState =>
          sys.error(s"Expected state with new project addition, got ReturnPreviousState")
      }
      .doOnFinish {
        // Delete only if it exists in case there has been an early exception
        case _ =>
          Task {
            Files.deleteIfExists(pathOfDummyFile)
            ()
          }
      }

    TestUtil.blockOnTask(checkTask, 5)
  }

  @Test
  def reloadWhenExistingConfigurationFilesChange(): Unit = {
    val logger = new RecordingLogger()
    val BuildLoaderState(state, _) = loadBuildState(logger)
    val projectsToModify = state.build.projects.take(2)
    val backups = projectsToModify.map(p => p -> p.origin.path.readAllBytes)

    val changes = backups.map {
      case (p, bytes) =>
        Task {
          val newContents = (new String(bytes)).replace(p.name, s"${p.name}z")
          Files.write(p.origin.path.underlying, newContents.getBytes(StandardCharsets.UTF_8))
        }
    }

    val checkTask = Task
      .gatherUnordered(changes)
      .flatMap { _ =>
        state.build.checkForChange(logger).map {
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
      .doOnFinish {
        case _ =>
          Task {
            // Restore the contents of the configuration files before the test
            backups.foreach {
              case (p, bytes) => Files.write(p.origin.path.underlying, bytes)
            }
          }
      }

    TestUtil.blockOnTask(checkTask, 5)
  }

  @Test
  def reloadWhenConfigurationFileIsDeleted(): Unit = {
    val logger = new RecordingLogger()
    val BuildLoaderState(state, configurationFile :: _) = loadBuildState(logger)
    val backup = configurationFile.readAllBytes

    val change = Task {
      Files.delete(configurationFile.underlying)
    }

    val checkTask = change
      .flatMap { _ =>
        state.build.checkForChange(logger).map {
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
              s"Expected state with deletion of ${configurationFile}, got ReturnPreviousState")
        }
      }
      .doOnFinish {
        case _ =>
          Task {
            Files.write(configurationFile.underlying, backup)
            ()
          }
      }

    TestUtil.blockOnTask(checkTask, 5)
  }

  case class BuildLoaderState(state: State, configurationFiles: List[AbsolutePath])
  def loadBuildState(logger: Logger): BuildLoaderState = {
    val state = TestUtil.loadTestProject("lichess").copy(logger = logger)
    val configDir = state.build.origin
    val configurationFiles = Paths.pathFilesUnder(configDir, BuildLoader.JsonFilePattern, 1)
    BuildLoaderState(state, configurationFiles)
  }
}
