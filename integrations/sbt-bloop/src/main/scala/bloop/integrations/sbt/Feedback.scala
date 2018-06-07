package bloop.integration.sbt

import sbt.{ProjectRef, ResolvedProject}

object Feedback {
  def unknownConfigurations(p: ResolvedProject, confs: Seq[String], from: ProjectRef): String = {
    s"""Unsupported dependency '${p.id}' -> '${from.project}:${confs.mkString(", ")}' is understood as '${from.project}:test'.""".stripMargin
  }

  def warnReferenceToClassesDir(scalacOption: String, oldClassesDir: String): String = {
    s"Replacing '$oldClassesDir' reference in '$scalacOption' by Bloop's new classes directory."
  }
}
