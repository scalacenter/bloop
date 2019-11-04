package bloop.integrations.sbt

import Compat.CompileAnalysis
import sbt.{Def, Task, TaskKey, Compile, Test, Keys, File, Classpaths}

object Offloader {
  val bloopAnalysisOut: Def.Initialize[Task[Option[File]]] = Def.task(None)

  val compile: Def.Initialize[Task[CompileAnalysis]] = Def.task(CompileAnalysis.Empty)
  val compileIncremental: Def.Initialize[Task[CompileAnalysis]] = Def.task(CompileAnalysis.Empty)
  val bloopCompile: Def.Initialize[Task[CompileAnalysis]] = Def.task(CompileAnalysis.Empty)
  val bloopOffloadCompilationTask: Def.Initialize[Task[CompileAnalysis]] =
    Def.task(CompileAnalysis.Empty)

  val bloopCompileProjectSettings: Seq[Def.Setting[_]] = List()
  val bloopCompileGlobalSettings: Seq[Def.Setting[_]] = List()
  val bloopCompileConfigSettings: Seq[Def.Setting[_]] = List(
    BloopKeys.bloopCompile := Keys.compileIncremental.value
  )
}
