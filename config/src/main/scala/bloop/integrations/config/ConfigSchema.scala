package bloop.integrations.config

import java.nio.file.{Path, Paths}

object ConfigSchema {
  private final val emptyPath = Paths.get("")
  case class JavaConfig(options: List[String])
  object JavaConfig { private[config] val empty = JavaConfig(Nil) }

  case class JvmConfig(home: Option[Path], options: List[String])
  object JvmConfig { private[config] val empty = JvmConfig(None, Nil) }

  case class TestFrameworkConfig(names: List[String])
  case class TestArgumentConfig(args: List[String], framework: Option[String])
  case class TestOptionsConfig(excludes: List[String], arguments: List[TestArgumentConfig])

  case class TestConfig(frameworks: List[TestFrameworkConfig], options: TestOptionsConfig)
  object TestConfig { private[config] val empty = TestConfig(Nil, TestOptionsConfig(Nil, Nil)) }

  case class ScalaConfig(
      organization: String,
      name: String,
      version: String,
      options: List[String],
      jars: List[Path]
  )

  object ScalaConfig {
    private[config] val empty: ScalaConfig = ScalaConfig("", "", "", Nil, Nil)
  }

  case class ProjectConfig(
      name: String,
      directory: Path,
      sources: List[Path],
      dependencies: List[String],
      classpath: List[Path],
      out: Path,
      `scala`: ScalaConfig,
      jvm: JvmConfig,
      java: JavaConfig,
      test: TestConfig,
  )

  object ProjectConfig {
    private[config] val empty: ProjectConfig = ProjectConfig(
      "",
      emptyPath,
      Nil,
      Nil,
      Nil,
      emptyPath,
      ScalaConfig.empty,
      JvmConfig.empty,
      JavaConfig.empty,
      TestConfig.empty
    )
  }
}
