package example

import org.scalatest._

class HelloSpec extends FlatSpec with Matchers with BeforeAndAfterAllConfigMap {
  override def beforeAll(configMap: ConfigMap): Unit =
    configMap.get("key").foreach(value => println(s"HelloSpec config key=$value"))

  "The Hello object" should "say hello" in {
    Hello.greeting shouldEqual "hello"
  }
}
