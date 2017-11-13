// copied from scalafix
package build

import sbt.{Keys, State, Project, ProjectRef, CrossVersion, Command, Def, ThisBuild}

/** Command to automatically enable semanticdb-scalac for shell session */
object Semanticdb {
  def partialToFullScalaVersion(scalaVersions: List[String]): Map[(Long, Long), String] = {
    (for {
      v <- scalaVersions
      p <- CrossVersion.partialVersion(v).toList
    } yield p -> v).toMap
  }

  def projectsWithMatchingScalaVersion(state: State,
                                       scalaVersions: List[String]): Seq[(ProjectRef, String)] = {
    val extracted = Project.extract(state)
    for {
      p              <- extracted.structure.allProjectRefs
      version        <- Keys.scalaVersion.in(p).get(extracted.structure.data).toList
      partialVersion <- CrossVersion.partialVersion(version).toList
      fullVersion <- partialToFullScalaVersion(scalaVersions)
        .get(partialVersion)
        .toList
    } yield p -> fullVersion
  }

  import sbt.librarymanagement.syntax.stringToOrganization
  def command(scalaVersions: List[String]) =
    Command.command(
      "semanticdbEnable",
      briefHelp = "Configure libraryDependencies, scalaVersion and scalacOptions for scalafix.",
      detail = """1. enables the semanticdb-scalac compiler plugin
                 |2. sets scalaVersion to latest Scala version supported by scalafix
                 |3. add -Yrangepos to scalacOptions""".stripMargin
    ) { s =>
      val extracted = Project.extract(s)
      val settings: Seq[Def.Setting[_]] = for {
        (p, fullVersion) <- projectsWithMatchingScalaVersion(s, scalaVersions)
        isEnabled = Keys.libraryDependencies
          .in(p)
          .get(extracted.structure.data)
          .exists(_.exists(_.name == "semanticdb-scalac"))
        if !isEnabled
        setting <- List(
          Keys.scalaVersion.in(p) := fullVersion,
          Keys.scalacOptions.in(p) ++= List(
            "-Yrangepos",
            s"-Xplugin-require:semanticdb",
            s"-P:semanticdb:sourceroot:${Keys.baseDirectory.in(ThisBuild).value.getAbsolutePath}"
          ),
          Keys.libraryDependencies.in(p) += sbt.compilerPlugin(
            "org.scalameta" % "semanticdb-scalac" % "2.1.2" cross CrossVersion.full)
        )
      } yield setting

      val semanticdbInstalled = extracted.append(settings, s)
      semanticdbInstalled
    }
}
