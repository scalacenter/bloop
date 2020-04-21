package bloop.bsp

import bloop.cli.BspProtocol
import bloop.data.WorkspaceSettings
import bloop.logging.RecordingLogger
import bloop.tracing.TraceProperties
import bloop.util.{CrossPlatform, TestProject, TestUtil}
import ch.epfl.scala.bsp.WorkspaceBuildTargetsResult

object LocalBspIntellijClientSpec extends BspIntellijClientSpec(BspProtocol.Local)
object TcpBspIntellijClientSpec extends BspIntellijClientSpec(BspProtocol.Tcp)

class BspIntellijClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {

  test("refresh project data on buildTargets request") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", Nil)
      val `B` = TestProject(workspace, "b", Nil)
      val initialProjects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, initialProjects)

      val refreshProjectsCommand = {
        val tmp = configDir.resolve("b.tmp")
        writeFile(tmp, `B`.toJson)

        val path = configDir.resolve("b.json")
        val addProjectBCommand = {
          val move = if (CrossPlatform.isWindows) "move" else "mv"
          move + s" $tmp $path"
        }
        // as settings are read when project is initialized, with this command
        // we simulate that project 'b' is added after first request

        val firstRunMarkerPath = configDir.resolve("first-run-marker")
        if (CrossPlatform.isWindows) {
          List(
            "cmd.exe",
            "/c",
            s"if not exist $firstRunMarkerPath (echo dummy > $firstRunMarkerPath) else ($addProjectBCommand)"
          )
        } else {
          List(
            "bash",
            "-c",
            s"[ ! -f $firstRunMarkerPath ] && touch $firstRunMarkerPath || $addProjectBCommand"
          )
        }
      }

      val workspaceSettings =
        WorkspaceSettings(
          None,
          None,
          refreshProjectsCommand = Some(refreshProjectsCommand),
          TraceProperties.default
        )
      WorkspaceSettings.writeToFile(configDir, workspaceSettings, logger)

      loadBspState(workspace, initialProjects, logger, bspClientName = "IntelliJ") { state =>
        def projectNames(w: WorkspaceBuildTargetsResult) = w.targets.flatMap(_.displayName)

        val initialTargets = state.workspaceTargets
        assertEquals(projectNames(initialTargets), List("a"))
        val finalTargets = state.workspaceTargets
        assertEquals(projectNames(finalTargets), List("a", "b"))
      }
    }
  }

}
