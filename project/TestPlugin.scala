import sbt._
import Keys._
import bloop.integrations.sbt.SbtBloop
import SbtBloop.{autoImport => BloopKeys}

object TestPlugin extends AutoPlugin {
  override def requires = SbtBloop
  override def trigger = allRequirements

  object autoImport {
    lazy val copyContentOutOfScripted =
      taskKey[Unit]("Copy all generated sources and resources out of scripted")
    lazy val copyContentOutOfScriptedIndividual =
      taskKey[Unit]("Copy generated sources and resources out of scripted")
  }
  import autoImport._

  override def globalSettings: Seq[Setting[_]] = Seq(
    copyContentOutOfScripted := Def.taskDyn {
      val filter = ScopeFilter(sbt.inAnyProject)
      copyContentOutOfScriptedIndividual.all(filter).map(_ => ())
    }.value
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    copyContentOutOfScriptedIndividual := {
      val outOfScriptedRoot = BloopKeys.bloopConfigDir.value.getParentFile
      val inScriptedRoot = baseDirectory.in(ThisBuild).value
      def copy(toCopy: Seq[File]): Unit = {
        for {
          src <- toCopy
          toCopyPath <- IO.relativize(inScriptedRoot, src)
          pathOutOfScripted = outOfScriptedRoot / toCopyPath
        } {
          if (src.isDirectory) IO.copyDirectory(src, pathOutOfScripted)
          else IO.copyFile(src, pathOutOfScripted)
        }
      }
      val allManagedSources = (managedSources in Compile).value ++ (managedSources in Test).value
      val allResources = {
        val resources = (copyResources in Compile).value ++ (copyResources in Test).value
        val (_, copied) = resources.unzip
        copied
      }
      copy(allManagedSources ++ allResources)
    }
  )
}
