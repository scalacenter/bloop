package bloop

import bloop.testing.BaseSuite
import bloop.util.CommandsDocGenerator

object CommandsDocGeneratorSpec extends BaseSuite {
  private def generated: String = {
    val examples = CommandsDocGenerator.parseExamples match {
      case Right(examples) => examples
      case Left(msg) => fail(msg)
    }
    CommandsDocGenerator.generateHTML(examples).mkString("\n")
  }

  test("render command names in headings") {
    val docs = generated
    assertContains(docs, "## `bloop about`")
    assertContains(docs, "## `bloop compile`")
    assertNotContains(docs, "List(")
  }

  test("render option types") {
    val docs = generated
    assertContains(docs, "<dt><code>--verbose</code> (type: <code>bool</code>)</dt>")
    assertContains(docs, "<dt><code>--jvm-debug</code> (type: <code>int?</code>)</dt>")
  }
}
