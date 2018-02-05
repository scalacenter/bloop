val ApacheSpark = RootProject(
  uri("git://github.com/scalacenter/spark.git#4e037d614250b855915c28ac1e84471075293124"))
val LihaoyiUtest = RootProject(
  uri("git://github.com/lihaoyi/utest.git#b5440d588d5b32c85f6e9392c63edd3d3fed3106"))
val ScalaScala = RootProject(
  uri("git://github.com/scalacenter/scala.git#6809ff601ed2efc81d3d0a199592f178429bf2ec"))
val ScalaCenterVersions = RootProject(
  uri("git://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))

val allIntegrationProjects = List(ApacheSpark, LihaoyiUtest, ScalaScala, ScalaCenterVersions)

// No changes should be needed below this point

val bloopConfigDir = settingKey[File]("...")
val installBloop = taskKey[Unit]("...")
val bloopGenerate = taskKey[Unit]("...")
val copyConfigs = taskKey[Unit]("Copy bloop configurations")

val integrations = project
  .in(file("."))
  .aggregate(allIntegrationProjects: _*)
  .settings(
    bloopGenerate in Compile := (),
    bloopGenerate in Test := (),
    copyConfigs := Def.taskDyn {
      val tasks = allIntegrationProjects.map(p => copyTask(p))
      join(tasks)
    }.value
  )

installBloop in Global := {
  (installBloop in allIntegrationProjects.head in Compile in Global).value
}

def copyTask(proj: RootProject) = Def.task {
  // I get `NoSuchMethodError` when using `split` ?!?
  // val name = proj.build.getPath.split("/").last.drop(4)
  val name = proj.build.getPath.reverse.drop(4).takeWhile(_ != '/').reverse
  val target = file(
    sys.env.get("bloop_target").getOrElse(sys.error("Missing environment variable: bloop_target")))
  val dest = target / name / ".bloop-config"
  val config = (bloopConfigDir in Compile in proj).value

  // We need to write where to find the sources of this project for benchmarking with scalac
  // and sbt.
  val base = config.getParentFile
  val baseConfig = target / name / "base-directory"
  IO.write(baseConfig, base.getAbsolutePath)

  streams.value.log.info(s"Copying $config to $dest")
  IO.copyDirectory(config, dest, overwrite = true)
}

import Def.Initialize
def join[T](tasks: Seq[Initialize[Task[T]]]): Initialize[Task[Seq[T]]] =
  Def.settingDyn {
    // First we join all the setting intializations into a single one with all the raw tasks.
    val zero: Initialize[Seq[Task[T]]] = Def.setting(Nil)
    val folded: Initialize[Seq[Task[T]]] = tasks.foldLeft(zero) { (acc, current) =>
      acc.zipWith(current) { case (taskSeq, task) => task +: taskSeq }
    }
    // Now we call the appropraite join task method.
    folded.apply { tasks: Seq[Task[T]] =>
      tasks.join
    }
  }
