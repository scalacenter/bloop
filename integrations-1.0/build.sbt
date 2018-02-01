val SbtSbt = RootProject(
  uri("git://github.com/sbt/sbt.git#f07434a3eb1f0c78c6cf8a62b27ee2a55c63a35a"))
val GuardianFrontend = RootProject(
  uri("git://github.com/guardian/frontend.git#c6611d93635133a8a0282c4ffc8d304effdca374"))
val MiniBetterFiles = RootProject(
  uri(
    "git://github.com/scalacenter/mini-better-files.git#0ed848993a2fd5a36e4366b5efb9c68dce958fc2"))
val WithResources = RootProject(
  uri("git://github.com/scalacenter/with-resources.git#7529b2c3ac455cbb1889d4791c4e0d4957e29306"))
val WithTests = RootProject(
  uri("git://github.com/scalacenter/with-tests.git#7a0c7f7d38efd53ca9ec3a347df3638932bd619e"))

val allIntegrationProjects =
  List(SbtSbt, GuardianFrontend, MiniBetterFiles, WithResources, WithTests)

val scalafmtOnCompile = settingKey[Boolean]("...")

scalafmtOnCompile in Global := false

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
  val name = proj.build.getPath.split("/").last.dropRight(4)
  val target = file(
    sys.env.get("bloop_target").getOrElse(sys.error("Missing environment variable: bloop_target")))
  val dest = target / name / ".bloop-config"
  val config = (bloopConfigDir in Compile in proj).value

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
