package bloop.integrations.config

import java.nio.file.{Path, Paths}

object ConfigSchema {
  private final val emptyPath = Paths.get("")

  case class JavaConfig(options: Seq[String])
  object JavaConfig { private[config] val empty = JavaConfig(Nil) }

  case class JvmConfig(home: Path, options: Seq[String])
  object JvmConfig { private[config] val empty = JvmConfig(emptyPath, Nil) }

  case class TestFrameworkConfig(names: Seq[String])
  case class TestArgumentConfig(args: Seq[String], framework: Option[String])
  case class TestOptionsConfig(excludes: Seq[String], arguments: Seq[TestArgumentConfig])

  case class TestConfig(frameworks: Seq[TestFrameworkConfig], options: TestOptionsConfig)
  object TestConfig { private[config] val empty = TestConfig(Nil, TestOptionsConfig(Nil, Nil)) }

  case class ScalaConfig(
      organization: String,
      name: String,
      version: String,
      options: Seq[String],
      jars: Seq[Path]
  )

  object ScalaConfig {
    private[config] val empty: ScalaConfig = ScalaConfig("", "", "", Nil, Nil)
  }

  case class ProjectConfig(
      name: String,
      directory: Path,
      sources: Seq[Path],
      dependencies: Seq[String],
      classpath: Seq[Path],
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
