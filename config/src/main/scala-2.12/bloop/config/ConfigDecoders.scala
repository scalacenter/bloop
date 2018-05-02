package bloop.config

import java.nio.file.{Files, Path, Paths}

import bloop.config.Config._
import metaconfig.generic.{Field, Surface}
import metaconfig.{Conf, ConfDecoder, ConfError, Configured, generic}

import scala.util.{Failure, Success, Try}

object ConfigDecoders {
  implicit val pathDecoder: ConfDecoder[Path] = ConfDecoder.stringConfDecoder.flatMap { str =>
    Try(Paths.get(str)) match {
      case Success(path) => Configured.Ok(path)
      case Failure(t) => Configured.error(s"Invalid path '$str' failed with '${t.getMessage}'")
    }
  }

  implicit val javaConfigSurface: Surface[Java] =
    generic.deriveSurface[Java]
  implicit val javaConfigDecoder: ConfDecoder[Java] =
    generic.deriveDecoder[Java](Java.empty)

  implicit val jvmConfigSurface: Surface[Jvm] =
    generic.deriveSurface[Jvm]
  implicit val jvmConfigDecoder: ConfDecoder[Jvm] =
    generic.deriveDecoder[Jvm](Jvm.empty)

  implicit val testFrameworkConfigSurface: Surface[TestFramework] =
    generic.deriveSurface[TestFramework]
  implicit val testFrameworkConfigDecoder: ConfDecoder[TestFramework] =
    generic.deriveDecoder[TestFramework](TestFramework.empty)

  implicit val testArgumentConfigSurface: Surface[TestArgument] =
    generic.deriveSurface[TestArgument]
  implicit val testArgumentConfigDecoder: ConfDecoder[TestArgument] =
    generic.deriveDecoder[TestArgument](TestArgument.empty)

  implicit val testOptionsConfigSurface: Surface[TestOptions] =
    generic.deriveSurface[TestOptions]
  implicit val testOptionsConfigDecoder: ConfDecoder[TestOptions] =
    generic.deriveDecoder[TestOptions](TestOptions.empty)

  implicit val testConfigSurface: Surface[Test] =
    generic.deriveSurface[Test]
  implicit val testConfigDecoder: ConfDecoder[Test] =
    generic.deriveDecoder[Test](Test.empty)

  implicit val scalaConfigSurface: Surface[Scala] =
    generic.deriveSurface[Scala]
  implicit val scalaConfigDecoder: ConfDecoder[Scala] =
    generic.deriveDecoder[Scala](Scala.empty)

  implicit val classpathOptionsConfigSurface: Surface[ClasspathOptions] =
    generic.deriveSurface[ClasspathOptions]
  implicit val classpathOptionsConfigDecoder: ConfDecoder[ClasspathOptions] =
    generic.deriveDecoder[ClasspathOptions](ClasspathOptions.empty)

  implicit val compileOrderConfigSurface: Surface[CompileOrder] = {
    val field = new Field("compileOrder", "string", Nil, Nil)
    new Surface[CompileOrder](List(List(field)))
  }

  implicit val compileOrderConfigDecoder: ConfDecoder[CompileOrder] = {
    new ConfDecoder[CompileOrder] {
      override def read(conf: Conf): Configured[CompileOrder] = {
        conf match {
          case Conf.Str("mixed") | Conf.Str("Mixed") => Configured.ok(Mixed)
          case Conf.Str("java->Scala") => Configured.ok(JavaThenScala)
          case Conf.Str("scala->java") => Configured.ok(ScalaThenJava)
          case Conf.Str(unknown) => Configured.error(s"Unknown compile order $unknown")
          case conf => Configured.typeMismatch("string", conf)
        }
      }
    }
  }

  implicit val compileOptionsConfigSurface: Surface[CompileOptions] =
    generic.deriveSurface[CompileOptions]
  implicit val compileOptionsConfigDecoder: ConfDecoder[CompileOptions] =
    generic.deriveDecoder[CompileOptions](CompileOptions.empty)

  implicit val projectConfigSurface: Surface[Project] =
    generic.deriveSurface[Project]
  implicit val projectConfigDecoder: ConfDecoder[Project] =
    generic.deriveDecoder[Project](Project.empty)

  implicit val allConfigSurface: Surface[File] =
    generic.deriveSurface[File]
  implicit val allConfigDecoder: ConfDecoder[File] =
    generic.deriveDecoder[File](File.empty)
}
