package bloop.logging

sealed trait LoggerAction
object LoggerAction {
  final case class LogErrorMessage(msg: String) extends LoggerAction
  final case class LogWarnMessage(msg: String) extends LoggerAction
  final case class LogInfoMessage(msg: String) extends LoggerAction
  final case class LogDebugMessage(msg: String) extends LoggerAction
  final case class LogTraceMessage(msg: String) extends LoggerAction
}
