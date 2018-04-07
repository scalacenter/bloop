package bloop.tasks

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.experimental.categories.Category

import bloop.{ScalaInstance}
import bloop.engine.State
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil.{
  checkAfterCleanCompilation,
  getProject,
  hasPreviousResult,
  noPreviousResult,
  RootProject
}

@Category(Array(classOf[bloop.FastTests]))
class CompilationTaskTest {
  object ArtificialSources {
    val `A.scala` = "package p0\nclass A"
    val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
    val `B2.scala` = "package p1\ntrait B"
    val `C.scala` = "package p2\nimport p0.A\nimport p1.B\nobject C extends A with B"
    val `C2.scala` = "package p2\nimport p0.A\nobject C extends A"
  }

  @Test
  def compileAnEmptyProject = {
    val projectStructures = Map(RootProject -> Map.empty[String, String])
    val dependencies = Map.empty[String, Set[String]]
    checkAfterCleanCompilation(projectStructures, dependencies, quiet = true) { (state: State) =>
      val targetProject = getProject(RootProject, state)
      assertTrue(hasPreviousResult(targetProject, state))
    }
  }

  @Test
  def compileWithScala2124 = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.4", logger)
    simpleProject(scalaInstance)
  }

  @Test
  def compileWithScala2123 = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.12.3", logger)
    simpleProject(scalaInstance)
  }

  @Test
  def compileWithScala21111 = {
    val logger = new RecordingLogger
    val scalaInstance =
      ScalaInstance.resolve("org.scala-lang", "scala-compiler", "2.11.11", logger)
    simpleProject(scalaInstance)
  }

  @Test
  def compileTwoProjectsWithADependency = {
    val projectsStructure = Map(
      "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      RootProject -> Map("B.scala" -> ArtificialSources.`B.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent"))
    checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
      }
    }
  }

  @Test
  def compileOneProjectWithTwoDependencies = {
    val projectsStructure = Map(
      "parent0" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "parent1" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent0", "parent1"))
    checkAfterCleanCompilation(projectsStructure, dependencies, quiet = true) { (state: State) =>
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
      }
    }
  }

  @Test
  def unnecessaryProjectsAreNotCompiled = {
    val projectsStructures = Map(
      "parent" -> Map("A.scala" -> ArtificialSources.`A.scala`),
      "unrelated" -> Map("B2.scala" -> ArtificialSources.`B2.scala`),
      RootProject -> Map("C.scala" -> ArtificialSources.`C2.scala`)
    )

    val dependencies = Map(RootProject -> Set("parent"))
    checkAfterCleanCompilation(projectsStructures, dependencies, quiet = true) { (state: State) =>
      // The unrelated project should not have been compiled
      assertTrue(s"Project `unrelated` was compiled",
                 noPreviousResult(getProject("unrelated", state), state))
      assertTrue(s"Project `parent` was not compiled",
                 hasPreviousResult(getProject("parent", state), state))
      assertTrue(s"Project `RootProject` was not compiled",
                 hasPreviousResult(getProject(RootProject, state), state))
    }
  }

  @Test
  def noResultWhenCompilationFails = {
    val projectsStructure = Map(RootProject -> Map("Error.scala" -> "iwontcompile"))
    checkAfterCleanCompilation(projectsStructure, Map.empty, failure = true) { (state: State) =>
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} has a compilation result", noPreviousResult(p, state))
      }
    }
  }

  @Test
  def compileDiamondLikeStructure = {
    object Sources {
      val `A.scala` = "package p0\nclass A"
      val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
      val `C.scala` = "package p2\nimport p0.A\nclass C extends A"
      val `D.scala` = "package p3\ntrait D"
      val `E.scala` = "package p3\nimport p1.B\nimport p2.C\nimport p3.D\nobject E extends B with D"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      "B" -> Map("B.scala" -> Sources.`B.scala`),
      "C" -> Map("C.scala" -> Sources.`C.scala`),
      "D" -> Map("D.scala" -> Sources.`D.scala`),
      RootProject -> Map("E.scala" -> Sources.`E.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(RootProject -> Set("A", "B", "C", "D"),
                   "B" -> Set("A"),
                   "C" -> Set("A"),
                   "D" -> Set("B", "C"))
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      val compilingInfos =
        logger.getMessages.filter(m => m._1 == "info" && m._2.contains("Compiling "))
      assert(compilingInfos.size == 5, "Bloop compiled more projects than necessary!")
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
      }
    }
  }

  @Test
  def compileRepeatedSubTreeInProjects = {
    object Sources {
      val `A.scala` = "package p0\nclass A"
      val `B.scala` = "package p1\nimport p0.A\nclass B extends A"
      val `C.scala` = "package p2\nimport p1.B\nclass C extends B"
      val `D.scala` = "package p3\nimport p1.B\nclass D extends B"
      val `E.scala` = "package p4\nimport p2.C\nimport p3.D\nobject E extends C { println(new D) }"
    }

    val structure = Map(
      "A" -> Map("A.scala" -> Sources.`A.scala`),
      "B" -> Map("B.scala" -> Sources.`B.scala`),
      "C" -> Map("C.scala" -> Sources.`C.scala`),
      "D" -> Map("D.scala" -> Sources.`D.scala`),
      RootProject -> Map("E.scala" -> Sources.`E.scala`)
    )

    val logger = new RecordingLogger
    val deps = Map(RootProject -> Set("A", "B", "C", "D"),
                   "B" -> Set("A"),
                   "C" -> Set("B", "A"),
                   "D" -> Set("B", "A"))
    checkAfterCleanCompilation(structure, deps, useSiteLogger = Some(logger)) { (state: State) =>
      val compilingInfos =
        logger.getMessages.filter(m => m._1 == "info" && m._2.contains("Compiling "))
      assert(compilingInfos.size == 5, "Bloop compiled more projects than necessary!")
      state.build.projects.foreach { p =>
        assertTrue(s"${p.name} was not compiled", hasPreviousResult(p, state))
      }
    }
  }

  private def simpleProject(scalaInstance: ScalaInstance): Unit = {
    val dependencies = Map.empty[String, Set[String]]
    val structures = Map(RootProject -> Map("A.scala" -> "object A"))
    // Scala bug to report: removing `(_ => ())` fails to compile.
    checkAfterCleanCompilation(structures,
                               dependencies,
                               scalaInstance = scalaInstance,
                               quiet = true)(_ => ())
  }
}
