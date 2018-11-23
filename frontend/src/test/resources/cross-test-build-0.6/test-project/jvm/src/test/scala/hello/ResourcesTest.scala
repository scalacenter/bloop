package hello

import org.scalatest._

import AppWithResources.resourceContent

class ResourcesTest extends FlatSpec with Matchers {
  "Resources" should "be found" in {
    assert(resourceContent("my-compile-resource.txt") == Some("Content of my-compile-resource.txt"))
    assert(
      resourceContent("generated-compile-resource.txt") == Some(
        "Content of generated-compile-resource.txt"))

    assert(resourceContent("my-test-resource.txt") == Some("Content of my-test-resource.txt"))
    assert(
      resourceContent("generated-test-resource.txt") == Some(
        "Content of generated-test-resource.txt"))

    assert(resourceContent("non-existent-resource.txt") == None)
  }
}
