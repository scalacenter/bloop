package hello

import org.specs2._

class Specs2Test extends Specification {
  def is = s2"""

  This is a specification to check the `Hello` object.

  A greeting
    is very personal              $isPersonal
  """

  def isPersonal = {
    Hello.greet("Martin") must beEqualTo("Hello, Martin!")
  }
}
