package bloop.config

import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}

object Config {
  private final val emptyPath = Paths.get("")
  case class Java(options: Array[String])
  object Java { private[bloop] val empty = Java(Array()) }

  case class Jvm(home: Option[Path], options: Array[String])
  object Jvm { private[bloop] val empty = Jvm(None, Array()) }

  case class TestFramework(names: List[String])
  object TestFramework { private[bloop] val empty = TestFramework(Nil) }
  case class TestArgument(args: Array[String], framework: Option[TestFramework])
  object TestArgument { private[bloop] val empty = TestArgument(Array(), None) }
  case class TestOptions(excludes: List[String], arguments: List[TestArgument])
  object TestOptions { private[bloop] val empty = TestOptions(Nil, Nil) }

  case class Test(frameworks: Array[TestFramework], options: TestOptions)
  object Test { private[bloop] val empty = Test(Array(), TestOptions.empty) }

  case class ClasspathOptions(
      bootLibrary: Boolean,
      compiler: Boolean,
      extra: Boolean,
      autoBoot: Boolean,
      filterLibrary: Boolean
  )

  object ClasspathOptions {
    private[bloop] val empty: ClasspathOptions = ClasspathOptions(true, false, false, true, true)
  }

  case class Scala(
      organization: String,
      name: String,
      version: String,
      options: Array[String],
      jars: Array[Path]
  )

  object Scala {
    private[bloop] val empty: Scala = Scala("", "", "", Array(), Array())
  }

  case class Project(
      name: String,
      directory: Path,
      sources: Array[Path],
      dependencies: Array[String],
      classpath: Array[Path],
      classpathOptions: ClasspathOptions,
      out: Path,
      classesDir: Path,
      `scala`: Scala,
      jvm: Jvm,
      java: Java,
      test: Test
  )

  object Project {
    // FORMAT: OFF
    private[bloop] val empty: Project =
      Project("", emptyPath, Array(), Array(), Array(), ClasspathOptions.empty, emptyPath, emptyPath, Scala.empty, Jvm.empty, Java.empty, Test.empty)
    // FORMAT: ON
  }

  case class File(version: String, project: Project)
  object File {
    final val LatestVersion = "1.0.0"

    private[bloop] val empty = File(LatestVersion, Project.empty)
    private final val DefaultCharset = Charset.defaultCharset()

    def write(all: File, target: Path): Unit = {
      val contents = ConfigEncoders.allConfigEncoder(all).spaces4
      Files.write(target, contents.getBytes(DefaultCharset))
      ()
    }
  }
}
