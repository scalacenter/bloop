package bloop.data

case class LoadedBuild(projects : List[Project], workspaceSettings : Option[WorkspaceSettings])