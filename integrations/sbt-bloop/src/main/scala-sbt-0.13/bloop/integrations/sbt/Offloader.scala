package bloop.integrations.sbt

import Compat.CompileAnalysis
import sbt.{Def, Task, TaskKey, Compile, Test, Keys, File, Classpaths}

object Offloader {
  final lazy val bloopInitializeConnection: Def.Initialize[Unit] = Def.setting(())
  final lazy val bloopOffloadCompilationTask: Def.Initialize[Task[CompileAnalysis]] =
    Def.task(CompileAnalysis.Empty)
}
