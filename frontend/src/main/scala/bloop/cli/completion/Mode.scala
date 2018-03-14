package bloop.cli.completion

sealed trait Mode

/** The kind of items that should be returned for autocompletion */
object Mode {

  /** Query autocompletion of commands */
  case object Commands extends Mode

  /** Query autocompletion of project names */
  case object Projects extends Mode

  /** Query autocompletion of command flags */
  case object Flags extends Mode

  /** Query autocompletion of test names */
  case object TestsFQCN extends Mode

  /** Query autocompletion of main classes */
  case object MainsFQCN extends Mode
}
