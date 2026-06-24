package bloop.integration.sbt

import sbt.ProjectRef
import sbt.ResolvedProject

object Feedback {
  def unknownConfigurations(p: ResolvedProject, confs: Seq[String], from: ProjectRef): String = {
    s"""Unsupported dependency '${p.id}' -> '${from.project}:${confs.mkString(
        ", "
      )}' is conservatively interpreted as '${from.project}:test'.""".stripMargin
  }

  def warnReferenceToClassesDir(scalacOption: String, oldClassesDir: String): String = {
    s"Replacing '$oldClassesDir' reference in '$scalacOption' by Bloop's new classes directory."
  }

  def sourceFiltersRequireReimport(projectName: String): String = {
    s"Project '$projectName' uses source include/exclude filters, so its sources are exported " +
      "as an explicit list of files. Re-run bloopInstall (or re-import the build) after adding " +
      "or removing sources so that Bloop compiles the same files as sbt."
  }
}
