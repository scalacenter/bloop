package bloop.data

sealed trait LoadedProject

object LoadedProject {
  final case class RawProject(
      project: Project
  ) extends LoadedProject

  final case class ConfiguredProject(
      project: Project,
      originalProject: Project,
      settings: WorkspaceSettings
  ) extends LoadedProject
}
