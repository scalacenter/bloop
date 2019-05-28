package bloop.engine.tasks.compilation

import bloop.data.Project
import bloop.CompileProducts
import bloop.io.AbsolutePath

import scala.collection.mutable

import java.io.File

import xsbti.compile.PreviousResult
import bloop.PartialCompileProducts

case class CompileDependenciesData(
    dependencyClasspath: Array[AbsolutePath],
    dependentResults: Map[File, PreviousResult],
    allInvalidatedClassFiles: Set[File],
    allGeneratedClassFilePaths: Map[String, File]
) {
  def buildFullCompileClasspathFor(
      project: Project,
      readOnlyClassesDir: AbsolutePath,
      newClassesDir: AbsolutePath,
      newPicklesDir: AbsolutePath
  ): Array[AbsolutePath] = {
    // Important: always place new classes dir before read-only classes dir
    val classesDirs = Array(newClassesDir, readOnlyClassesDir)
    val resources = project.pickValidResources
    resources ++ classesDirs ++ dependencyClasspath
  }
}

object CompileDependenciesData {
  def compute(
      genericClasspath: Array[AbsolutePath],
      dependentProducts: Map[Project, Either[PartialCompileProducts, CompileProducts]]
  ): CompileDependenciesData = {
    val resultsMap = new mutable.HashMap[File, PreviousResult]()
    val dependentClassesDir = new mutable.HashMap[AbsolutePath, Array[AbsolutePath]]()
    val dependentResources = new mutable.HashMap[AbsolutePath, Array[AbsolutePath]]()
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

        dependentClassesDir.put(genericClassesDir, classesDirs.map(AbsolutePath(_)))
        dependentInvalidatedClassFiles.++=(products.invalidatedCompileProducts)
        dependentGeneratedClassFilePaths.++=(products.generatedRelativeClassFilePaths.iterator)
        dependentResources.put(genericClassesDir, project.pickValidResources)
        resultsMap.put(genericClassesDir.toFile, products.resultForDependentCompilationsInSameRun)
    }

    val addedResources = new mutable.HashSet[AbsolutePath]()
    val rewrittenClasspath = genericClasspath.flatMap { entry =>
      dependentClassesDir.get(entry) match {
        case Some(classesDirs) =>
          dependentResources.get(entry) match {
            case Some(existingResources) =>
              val newExistingResources =
                existingResources.filterNot(r => addedResources.contains(r))
              newExistingResources.foreach(r => addedResources.add(r))
              newExistingResources ++ classesDirs
            case None => classesDirs
          }
        case None => List(entry)
      }
    }

    CompileDependenciesData(
      rewrittenClasspath,
      resultsMap.toMap,
      dependentInvalidatedClassFiles.toSet,
      dependentGeneratedClassFilePaths.toMap
    )
  }
}
