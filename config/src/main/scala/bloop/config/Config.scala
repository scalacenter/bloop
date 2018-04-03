package bloop.config

import java.nio.file.{Path, Paths}

object Config {
  private final val emptyPath = Paths.get("")
  case class Java(options: List[String])
  object Java { private[bloop] val empty = Java(Nil) }

  case class Jvm(home: Option[Path], options: List[String])
  object Jvm { private[bloop] val empty = Jvm(None, Nil) }

  case class TestFramework(names: List[String])
  case class TestArgument(args: List[String], framework: Option[TestFramework])
  case class TestOptions(excludes: List[String], arguments: List[TestArgument])
  object TestOptions {
    private[bloop] val empty = TestOptions(Nil, Nil)
  }

  case class Test(frameworks: List[TestFramework], options: TestOptions)
  object Test { private[bloop] val empty = Test(Nil, TestOptions.empty) }

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
      options: List[String],
      jars: List[Path]
  )

  object Scala {
    private[bloop] val empty: Scala = Scala("", "", "", Nil, Nil)
  }

  case class Project(
      name: String,
      directory: Path,
      sources: List[Path],
      dependencies: List[String],
      classpath: List[Path],
      classpathOptions: ClasspathOptions,
      out: Path,
      `scala`: Scala,
      jvm: Jvm,
      java: Java,
      test: Test,
  )

  object Project {
    // FORMAT: OFF
    private[bloop] val empty: Project =
      Project("", emptyPath, Nil, Nil, Nil, ClasspathOptions.empty, emptyPath, Scala.empty, Jvm.empty, Java.empty, Test.empty)
    // FORMAT: ON
  }

  case class All(version: String, project: Project)
  object All {
    private[bloop] val empty = All(LatestVersion, Project.empty)
    final val LatestVersion = "1.0"
    def write(all: All, target: Path): Unit = {
      ???
    }
  }
}
