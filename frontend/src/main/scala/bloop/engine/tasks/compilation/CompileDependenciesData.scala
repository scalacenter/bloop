package bloop.engine.tasks.compilation

import java.io.File

import scala.collection.mutable

import bloop.CompileProducts
import bloop.PartialCompileProducts
import bloop.data.Project
import bloop.io.AbsolutePath

case class CompileDependenciesData(
    dependencyClasspath: Array[AbsolutePath],
    bestEffortDirs: Seq[AbsolutePath],
    allInvalidatedClassFiles: Set[File],
    allGeneratedClassFilePaths: Map[String, File]
) {
  def buildFullCompileClasspathFor(
      project: Project,
      readOnlyClassesDir: AbsolutePath,
      newClassesDir: AbsolutePath
  ): Array[AbsolutePath] = {
    // Important: always place new classes dir before read-only classes dir
    val classesDirs = Array(newClassesDir, readOnlyClassesDir)
    val resources = Project.pickValidResources(project.resources)
    resources ++ classesDirs ++ bestEffortDirs ++ dependencyClasspath
  }
}

object CompileDependenciesData {

  /**
   * `transitiveDependencies` is every project the compiled one depends on, directly or
   * transitively, excluding the project itself; its own resources are prepended by
   * `buildFullCompileClasspathFor`.
   */
  def compute(
      genericClasspath: Array[AbsolutePath],
      dependentProducts: Map[Project, Either[PartialCompileProducts, CompileProducts]],
      transitiveDependencies: List[Project]
  ): CompileDependenciesData = {
    val dependentClassesDir = new mutable.HashMap[AbsolutePath, Array[AbsolutePath]]()
    val dependentBestEffortDirs = new mutable.ArrayBuffer[AbsolutePath]()
    val dependentInvalidatedClassFiles = new mutable.HashSet[File]()
    val dependentGeneratedClassFilePaths = new mutable.HashMap[String, File]()
    dependentProducts.foreach {
      case (project, Left(products)) =>
        val newClassesDir = products.newClassesDir
        val genericClassesDir = project.genericClassesDir
        val readOnlyClassesDir = products.readOnlyClassesDir
        // Don't add pickle classes dir as we load signatures from memory
        val classesDirs = {
          // New classes dir must be first because it has priority over old classes dir
          if (newClassesDir == readOnlyClassesDir) Array(newClassesDir)
          else Array(newClassesDir, readOnlyClassesDir)
        }

        if (project.isBestEffort) {
          dependentBestEffortDirs ++= classesDirs.map(_.resolve("META-INF").resolve("best-effort"))
        }
        dependentClassesDir.put(genericClassesDir, classesDirs)
      case (project, Right(products)) =>
        val genericClassesDir = project.genericClassesDir
        val newClassesDir = products.newClassesDir
        val readOnlyClassesDir = products.readOnlyClassesDir
        val classesDirs = {
          // New classes dir must be first because it has priority over old classes dir
          if (newClassesDir == readOnlyClassesDir) Array(newClassesDir)
          else Array(newClassesDir, readOnlyClassesDir)
        }

        if (project.isBestEffort) {
          dependentBestEffortDirs ++= classesDirs
            .map(AbsolutePath(_).resolve("META-INF").resolve("best-effort"))
            .toSeq
        }
        dependentClassesDir.put(genericClassesDir, classesDirs.map(AbsolutePath(_)))
        dependentInvalidatedClassFiles.++=(products.invalidatedCompileProducts)
        dependentGeneratedClassFilePaths.++=(products.generatedRelativeClassFilePaths.iterator)
    }

    // Resources are compilation inputs, not compilation products, so they come from the
    // dependency graph rather than from `dependentProducts`. A dependency without sources
    // produces no products, yet a macro expanding in a dependent must still read its
    // resources, as it does under sbt.
    val dependentResources: Map[AbsolutePath, Array[AbsolutePath]] =
      transitiveDependencies.iterator
        .map(d => d.genericClassesDir -> Project.pickValidResources(d.resources))
        .toMap

    val addedResources = new mutable.HashSet[AbsolutePath]()
    val rewrittenClasspath = genericClasspath.flatMap { entry =>
      val classesDirs = dependentClassesDir.getOrElse(entry, Array(entry))
      dependentResources.get(entry) match {
        case Some(existingResources) =>
          val newExistingResources =
            existingResources.filterNot(r => addedResources.contains(r))
          newExistingResources.foreach(r => addedResources.add(r))
          newExistingResources ++ classesDirs
        case None => classesDirs
      }
    }

    CompileDependenciesData(
      rewrittenClasspath,
      dependentBestEffortDirs.toSeq,
      dependentInvalidatedClassFiles.toSet,
      dependentGeneratedClassFilePaths.toMap
    )
  }
}
