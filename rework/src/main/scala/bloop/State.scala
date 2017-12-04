package bloop

import bloop.{Project, CompilerCache}
import bloop.util.TopologicalSort
import bloop.io.{Paths, AbsolutePath}
import bloop.logging.Logger

import scala.concurrent.ExecutionContext

final case class BareProject(
    name: String,
    baseDirectory: AbsolutePath,
    dependencies: Array[String],
    scalaInstance: ScalaInstance,
    classpath: Array[AbsolutePath],
    classesDir: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    sourceDirectories: Array[AbsolutePath],
    testFrameworks: Array[Array[String]],
    tmp: AbsolutePath,
    origin: Option[AbsolutePath]
)

final case class Build private (
    loadedFrom: AbsolutePath,
    projects: Map[String, Project],
    dags: List[DAG[Project]]
) {
  def reachableFrom(from: Project): List[Project] =
    TopologicalSort.reachable(from, projects).values.toList
}

object Build {
  def apply(loadedFrom: AbsolutePath, projects: Map[String, Project]): Build = {
    val dags = DAG.fromMap(projects)
    new Build(loadedFrom, projects, dags)
  }
}

sealed abstract class State {
  def build: Build
  def logger: Logger

  protected def buildCache: BuildCache
  protected def compilerCache: CompilerCache
  protected def resultsCache: ResultsCache
  protected def executionContext: ExecutionContext
}

object Compat {
  implicit class JavaFunction[T, R](f: T => R) {
    import java.util.function.Function
    def toJava: Function[T, R] = new Function[T, R] {
      def apply(t: T): R = f(t)
    }
  }
}

import java.util.concurrent.ConcurrentHashMap
import xsbti.compile.PreviousResult
final class ResultsCache(cache: ConcurrentHashMap[Project, PreviousResult]) {
  import Compat.JavaFunction
  def getResultFor(project: Project): Option[PreviousResult] = Option(cache.get(project))
  def updateResult(project: Project, previousResult: PreviousResult): Unit =
    cache.put(project, previousResult)
  private val emptyGenerator = ((_: Project) => CompileState.EmptyCompileResult).toJava
  def initializeResult(project: Project): Unit = cache.computeIfAbsent(project, emptyGenerator)
}

object ResultsCache {
  def empty: ResultsCache = new ResultsCache(new ConcurrentHashMap())
}

sealed abstract class InitializedState extends State {
  protected final val resultsCache: ResultsCache = ResultsCache.empty
  protected final val buildCache: BuildCache = BuildCache.empty
  protected final val compilerCache: CompilerCache = {
    import sbt.internal.inc.bloop.ZincInternals
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"), logger)
  }
}

case class CompileState(build: Build, logger: Logger) extends InitializedState {
  override val executionContext: ExecutionContext = ???

  def compile(project: Project): State = ???
  def clean(projects: List[Project]): State = ???
  def persistAnalysis(projects: List[Project]): State = ???
}

object TestTasks {
  def test(state: State): State = ???
  def testOnly(state: State): State = ???
  def testQuick(state: State): State = ???
}

object CompileState {
  import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}
  import java.util.Optional
  private[bloop] val EmptyCompileResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])
}
