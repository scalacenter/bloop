package bloop.engine.tasks.compilation

import bloop.engine.Dag
import bloop.data.Project
import bloop.io.AbsolutePath

import java.nio.file.Files

/**
 * Maps compile out paths present in the original project configuration to the
 * actual classes directories where the compilation will be stored.
 *
 * Every bloop client has its own instance of this class until the client is
 * disconnected. This helps us isolate concurrent compilations of the same
 * build in different clients.
 *
 * The lifetime of the compile out directories is managed by
 * [[bloop.engine.tasks.CompileTask]].
 *
 * This entity also has access to all the resource directories of a project so
 * that resources can be added to the compile classpath. However, it does not
 * modify or create new resource paths.
 */
final class CompileOutPaths(
    uniqueOutPaths: Map[AbsolutePath, AbsolutePath],
    resourceDirectories: Map[AbsolutePath, List[AbsolutePath]]
) {
  def getOutputFor(project: Project): Option[AbsolutePath] = {
    uniqueOutPaths.get(project.classesDir)
  }

  /**
   * Rewrites a generic classpath that contains symlinks and other target
   * compile directories and instead replaces them by the paths available in
   * this [[CompileDirectories]] instance.
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
    val compilationPaths = Dag.dfs(dag).map { project =>
      val newClassesDir = Files.createTempDirectory(s"classes-${project.name}")
      (
        project.classesDir -> AbsolutePath(newClassesDir),
        project.classesDir -> project.resources
      )
    }

    val (classesDirMapping, resourcesMapping) = compilationPaths.unzip
    new CompileOutPaths(classesDirMapping.toMap, resourcesMapping.toMap)
  }
}
