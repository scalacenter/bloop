import java.nio.file.{Files, Path, Paths}
import munit.FunSuite

class ResourcesTest extends FunSuite {

  test("resources") {
    val p: Path = Paths.get(getClass.getResource("test.txt").toURI)
    assert(Files.exists(p))
  }

}
