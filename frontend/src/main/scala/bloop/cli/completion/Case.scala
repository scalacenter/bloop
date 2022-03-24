package bloop.cli.completion

import caseapp.Name
import caseapp.core.Arg

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

  def kebabizeArg(arg: Arg): Arg = {
    val kebabizedName = camelToKebab(arg.name.name)
    val kebabizedExtraNames = arg.extraNames.map((n: Name) => Name(camelToKebab(n.name)))
    arg.withName(Name(kebabizedName)).withExtraNames(kebabizedExtraNames)
  }
}
