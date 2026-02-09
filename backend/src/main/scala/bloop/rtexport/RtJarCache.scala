package bloop.rtexport

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import bloop.SemanticDBCacheLock
import bloop.io.Paths
import bloop.logging.Logger

import sbt.internal.inc.BloopComponentCompiler
import sbt.internal.inc.BloopComponentManager
import sbt.internal.inc.IfMissing
import bloop.io.AbsolutePath

object RtJarCache {

  def create(
      bloopJavaVersion: String,
      logger: Logger
  ): Option[AbsolutePath] = {

    val provider =
      BloopComponentCompiler.getComponentProvider(Paths.getCacheDirectory("rtjar"))
    val manager =
      new BloopComponentManager(SemanticDBCacheLock, provider, secondaryCacheDir = None)

    Try(manager.files(bloopJavaVersion)(IfMissing.Fail)) match {
      case Success(rtPath) => rtPath.headOption.map(AbsolutePath(_))
      case Failure(_) =>
        manager.define(bloopJavaVersion, Export.rt())
        Try(manager.files(bloopJavaVersion)(IfMissing.Fail)) match {
          case Failure(exception) =>
            logger.error(
              "Could not create rt.jar needed for correct compilation for JDK 8.",
              exception
            )
            None
          case Success(value) => value.headOption.map(AbsolutePath(_))
        }
    }
  }

}
