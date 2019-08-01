package bloop.data

sealed trait LoadedProject {
  def project: Project
}

object LoadedProject {

  /**
   * Represents a project that doesn't have any configuration applied to it.
   * For example, if the Metals client asks to enable the SemanticDB plugin for
   * all Scala projects, Bloop will skip configuring Java projects and wrap
   * them in raw projects.
   */
  final case class RawProject(
      project: Project
  ) extends LoadedProject

  /**
   * Represents a project that has been transformed in-memory. It also contains
   * the original project before the transformation took place and the settings
   * that were used for the transformation so that the build logic can detect
   * whether this configured project deserves to be reconfigured or not.
   */
  final case class ConfiguredProject(
      project: Project,
      original: Project,
      settings: WorkspaceSettings
  ) extends LoadedProject
}
