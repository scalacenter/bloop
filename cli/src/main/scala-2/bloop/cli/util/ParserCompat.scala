package bloop.cli.util

import caseapp.core.parser.{Parser => CaseParser}

object ParserCompat {

  /**
   * Scala 2 case-app pins the parser state in a refined type. `@Recurse` only sees it
   * when that refinement is the implicit, which `Parser.Aux` names.
   */
  type Aux[T, D0] = CaseParser[T] { type D = D0 }
}
