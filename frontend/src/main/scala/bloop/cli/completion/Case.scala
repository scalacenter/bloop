package bloop.cli.completion

import caseapp.core.{Arg}
import caseapp.Name

object Case {
  private val Kebab = "-([a-z])".r
  private val Camel = "([A-Z])".r

  private def camelToKebab(camel: String): String = {
    val m = Camel.pattern.matcher(camel)
    val sb = new StringBuffer
    while (m.find()) {
      m.appendReplacement(sb, "-" + m.group().toLowerCase())
    }
    m.appendTail(sb)
    sb.toString
  }

  def kebabizeArg(arg: Arg): Arg = arg.copy(extraNames =
                                              arg.extraNames.map((n: Name) => Name(camelToKebab(n.name))))
}
