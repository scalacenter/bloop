package bloop.cli

import java.io.{InputStream, PrintStream}
import java.util.Properties

import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import caseapp.Hidden

/**
 * Describes the common options for any command or CLI operation.
 *
 * They exist for two purposes: testing and nailgun. In both cases we
 * need a precise handling of these parameters because they change
 * depending on the environment we're running on.
 *
 * They are hidden because they are optional.
 */
case class CommonOptions(
    @Hidden workingDirectory: String = System.getProperty("user.dir"),
    @Hidden out: PrintStream = System.out,
    @Hidden in: InputStream = System.in,
    @Hidden err: PrintStream = System.err,
    @Hidden ngout: PrintStream = System.out,
    @Hidden ngerr: PrintStream = System.err,
    @Hidden env: CommonOptions.PrettyProperties = CommonOptions.currentEnv
) {
  def workingPath: AbsolutePath = AbsolutePath(workingDirectory)
}

object CommonOptions {
  final val default = CommonOptions()

  // Our own version of properties in which we override `toString`
  final class PrettyProperties extends Properties {
    override def toString: String = synchronized {
      super.keySet().toArray.map(_.toString).mkString(", ")
    }

    def toMap: Map[String, String] = {
      import scala.collection.JavaConverters._
      this.asScala.toMap
    }
  }

  object PrettyProperties {
    def from(p: Properties): PrettyProperties = {
      val pp = new PrettyProperties()
      // Scala bug #10418 work-around
      import scala.collection.JavaConverters._
      p.asScala.foreach {
        case (k, v) =>
          pp.setProperty(k, v)
      }
      
      pp
    }
  }

  final lazy val currentEnv: PrettyProperties = {
    import scala.collection.JavaConverters._
    System.getenv().asScala.foldLeft(new PrettyProperties()) {
      case (props, (key, value)) => props.setProperty(key, value); props
    }
  }
}
