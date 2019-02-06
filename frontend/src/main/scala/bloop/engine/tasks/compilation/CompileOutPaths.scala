package bloop.engine.tasks.compilation

import bloop.engine.Dag
import bloop.data.Project
import bloop.io.{AbsolutePath, RelativePath}

import java.nio.file.Files

/**
 *
 * A general-purpose entity that maps the original, shared paths used as
 * compilations outputs to unique client-specific compilation outputs.
 * The compilation outputs comprise target classes directories/target single
 * jars and analysis out paths (where the Zinc analysis file is written to).
 *
 * Every bloop client has its own instance of this class until the client is
 * disconnected. This helps us isolate concurrent compilations of the same
 * build in different clients.
 *
 * The lifetime of the compile out directories is managed by the callers of
 * [[bloop.engine.tasks.CompileTask]].
 *
 * This entity also has access to all the resource directories of a project so
 * that resources can be added to the compile classpath. However, it does not
 * modify or create new resource paths.
 */
final class CompileOutPaths(
    uniqueOutPaths: Map[AbsolutePath, AbsolutePath],
    uniqueAnalysisPaths: Map[AbsolutePath, AbsolutePath],
    resourceDirectories: Map[AbsolutePath, List[AbsolutePath]]
) {

  /**
   * Get the client-specific compilation output path mapped to this project
   * where the class files should be persisted to.
   */
  def getOutputFor(project: Project): Option[AbsolutePath] = {
    uniqueOutPaths.get(project.classesDir)
  }

  /**
   * Get the client-specific analysis out path mapped to this project where the
   * compilation analysis should be written to.
   */
  def getAnalysisOutFor(project: Project): Option[AbsolutePath] = {
    uniqueAnalysisPaths.get(project.analysisOut)
  }

  /**
   * Rewrites a raw classpath containing the original classes directories
   * to a classpath where only client-specific compilation paths are used.
   *
   * This rewriting is done before the compilation of any project to isolate
   * compilations of the same build from different clients. Read the docs of
   * [[CompileDirectories]] for more information.
   */
  def rewriteClasspath(genericClasspath: Array[AbsolutePath]): Array[AbsolutePath] = {
    genericClasspath.flatMap { entry =>
      uniqueOutPaths.get(entry) match {
        case Some(realClassesDir) =>
          resourceDirectories.get(entry) match {
            case Some(resources) =>
              // Resource dirs should have higher priority than out path
              resources.filter(_.exists).toArray ++ Array(realClassesDir)
            case None => Array(realClassesDir)
          }
        case None => List(entry)
      }
    }
  }
}

object CompileOutPaths {
  def apply(dag: Dag[Project]): CompileOutPaths = {
    var classesDirMapping = Map.empty[AbsolutePath, AbsolutePath]
    var resourcesMapping = Map.empty[AbsolutePath, List[AbsolutePath]]
    var analysisMapping = Map.empty[AbsolutePath, AbsolutePath]

    val compilationPaths = Dag.dfs(dag).foreach { project =>
      classesDirMapping = classesDirMapping + (project.classesDir -> project.classesDir)
      resourcesMapping = resourcesMapping + (project.classesDir -> project.resources)
      analysisMapping = analysisMapping + (project.analysisOut -> project.analysisOut)
    }

    new CompileOutPaths(classesDirMapping, analysisMapping, resourcesMapping)
  }

  def unique(dag: Dag[Project]): CompileOutPaths = {
    var classesDirMapping = Map.empty[AbsolutePath, AbsolutePath]
    var resourcesMapping = Map.empty[AbsolutePath, List[AbsolutePath]]
    var analysisMapping = Map.empty[AbsolutePath, AbsolutePath]

    def createNewPathInSameParent(target: AbsolutePath): AbsolutePath = {
      val newTarget = target.getParent.resolve(RelativePath(target.underlying.getFileName))
      if (target.exists) {
        // If target exists, create new target of the same type (if symlink, create file)
        if (target.isDirectory) Files.createDirectories(newTarget.underlying)
        else Files.createFile(newTarget.underlying)
      }
      newTarget
    }

    Dag.dfs(dag).foreach { project =>
      val originalClassesDir = project.classesDir
      val newClassesDir = createNewPathInSameParent(originalClassesDir)
      val originalAnalysisOut = project.analysisOut
      val newAnalysisOut = createNewPathInSameParent(originalAnalysisOut)
      classesDirMapping = classesDirMapping + (originalClassesDir -> newClassesDir)
      resourcesMapping = resourcesMapping + (project.classesDir -> project.resources)
      analysisMapping = analysisMapping + (originalAnalysisOut -> newAnalysisOut)
    }

    new CompileOutPaths(classesDirMapping, analysisMapping, resourcesMapping)
  }
}
