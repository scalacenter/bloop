package bloop.launcher

import java.nio.file.Path

sealed trait ServerState
case class AvailableAt(binary: String) extends ServerState
case class ResolvedAt(files: Seq[Path]) extends ServerState
case class ListeningAndAvailableAt(binary: String) extends ServerState

