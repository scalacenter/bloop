package bloop.cli.util

import caseapp.core.parser.{Parser => CaseParser}

object ParserCompat {

  /**
   * Scala 3 derives parsers from tuples and has no `Parser.Aux`. The same refinement still
   * type-checks, so companions can share the Scala 2 definition.
   */
  type Aux[T, D0 <: Tuple] = CaseParser[T] { type D = D0 }
}
