package bloop.launcher.core

import java.nio.file.Path

sealed trait ServerStatus
case class AvailableAt(binary: List[String]) extends ServerStatus
case class ResolvedAt(files: Seq[Path]) extends ServerStatus
case class ListeningAndAvailableAt(binary: List[String]) extends ServerStatus

