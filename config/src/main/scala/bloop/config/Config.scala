package bloop.config

import java.nio.file.{Path, Paths}

object Config {
  private final val emptyPath = Paths.get("")
  case class Java(options: List[String])
  object Java { private[config] val empty = Java(Nil) }

  case class Jvm(home: Option[Path], options: List[String])
  object Jvm { private[config] val empty = Jvm(None, Nil) }

  case class TestFramework(names: List[String])
  case class TestArgument(args: List[String], framework: Option[String])
  case class TestOptions(excludes: List[String], arguments: List[TestArgument])

  case class Test(frameworks: List[TestFramework], options: TestOptions)
  object Test { private[config] val empty = Test(Nil, TestOptions(Nil, Nil)) }

  case class Scala(
      organization: String,
      name: String,
      version: String,
      options: List[String],
      jars: List[Path]
  )

  object Scala {
    private[config] val empty: Scala = Scala("", "", "", Nil, Nil)
  }

  case class Project(
      name: String,
      directory: Path,
      sources: List[Path],
      dependencies: List[String],
      classpath: List[Path],
      out: Path,
      `scala`: Scala,
      jvm: Jvm,
      java: Java,
      test: Test,
  )

  object Project {
    private[config] val empty: Project = Project(
      "",
      emptyPath,
      Nil,
      Nil,
      Nil,
      emptyPath,
      Scala.empty,
      Jvm.empty,
      Java.empty,
      Test.empty
    )
  }
}
