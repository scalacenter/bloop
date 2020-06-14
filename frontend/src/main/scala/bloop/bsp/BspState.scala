package bloop.bsp

import bloop.cli.CommonOptions
import bloop.Compiler
import bloop.data.{ClientInfo, Project, WorkspaceSettings}
import bloop.engine.{ClientPool, State}
import bloop.io.AbsolutePath
import bloop.logging.Logger
import monix.eval.Task

import scala.annotation.tailrec

case class BspState(
    main: State,
    meta: List[State]
) { self =>

  val commonOptions: CommonOptions = main.commonOptions
  val logger: Logger = main.logger
  val pool: ClientPool = main.pool
  val client: ClientInfo = main.client
  val allBuildStates: List[State] = main :: meta

  def buildStateByBaseDirectory(path: AbsolutePath): Option[State] =
    allBuildStates.find(_.build.loadedProjects.exists(_.project.baseDirectory == path))

  def updateBuildStates(updated: List[State]): BspState = {
    val init = (Option.empty[State], List.empty[State])
    val (newMain, updMeta) = updated.foldLeft(init) {
      case ((None, metaAcc), st) if main.build.origin == st.build.origin => (Some(st), metaAcc)
      case ((x, metaAcc), st) => (x, st :: metaAcc)
    }

    val newMeta = {
      val curr = meta.map(v => v.build.origin -> v).toMap
      val upd = updMeta.map(v => v.build.origin -> v).toMap
      (curr ++ upd).values.toList
    }

    val applyMain = newMain.fold(self)(m => self.copy(main = m))
    applyMain.copy(meta = newMeta)
  }

  def updateLogger(logger: Logger): BspState = {
    copy(
      main = main.copy(logger = logger),
      meta = meta.map(_.copy(logger = logger))
    )
  }

  def updateClient(client: ClientInfo): BspState = {
    copy(
      main = main.copy(client = client),
      meta = meta.map(_.copy(client = client))
    )
  }

  def replacePreviousResults(
      previous: Map[(AbsolutePath, Project), Compiler.Result.Failed]
  ): BspState = {

    def replaceResults(st: State, previous: Map[Project, Compiler.Result.Failed]): State =
      st.copy(results = st.results.replacePreviousResults(previous))

    val init = Map.empty[AbsolutePath, Map[Project, Compiler.Result.Failed]]
    val prepareToReplace = previous.foldLeft(init) {
      case (acc, ((path, pr), results)) =>
        val curr = acc.getOrElse(path, Map.empty)
        acc.updated(path, curr.updated(pr, results))
    }

    prepareToReplace.foldLeft(self) {
      case (updState, (origin, previous)) if origin == updState.main.build.origin =>
        updState.copy(main = replaceResults(updState.main, previous))
      case (updState, (origin, previous)) =>
        updState.meta.find(_.build.origin == origin) match {
          case None => updState
          case Some(prevMeta) =>
            val dropOld = updState.meta.filter(_.build.origin == origin)
            updState.copy(meta = replaceResults(prevMeta, previous) :: dropOld)
        }
    }
  }
}

object BspState {

  def loadActiveStateFor(
      configDir: bloop.io.AbsolutePath,
      client: ClientInfo,
      pool: ClientPool,
      opts: CommonOptions,
      logger: Logger,
      clientSettings: Option[WorkspaceSettings] = None
  ): Task[BspState] = {

    val loadState = (dir: AbsolutePath) => {
      State.loadActiveStateFor(
        dir,
        client,
        pool,
        opts,
        logger,
        clientSettings
      )
    }

    val metaDirs = sbtDirectoriesRecursively(configDir.getParent, List.empty)

    val main = loadState(configDir)
    val meta = Task.gatherUnordered(metaDirs.map(loadState))

    Task.mapBoth(main, meta)(BspState(_, _))
  }

  @tailrec
  private def sbtDirectoriesRecursively(
      p: AbsolutePath,
      out: List[AbsolutePath]
  ): List[AbsolutePath] = {
    val projectDir = p.resolve("project")
    val sbtDir = projectDir.resolve(".bloop")
    if (sbtDir.exists && sbtDir.isDirectory)
      sbtDirectoriesRecursively(projectDir, sbtDir :: out)
    else
      out
  }
}
