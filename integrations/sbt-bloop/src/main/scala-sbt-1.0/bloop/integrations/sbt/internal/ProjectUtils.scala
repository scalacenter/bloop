package bloop.integrations.sbt.internal

import bloop.integrations.sbt.BloopProjectConfig

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

import java.nio.file.Path
import java.net.URI
import java.io.File
import java.{util => ju}
import java.lang.ref.SoftReference

import xsbti.compile.AnalysisStore
import xsbti.compile.AnalysisContents

import sbt.Def
import sbt.Task
import sbt.internal.inc.FileAnalysisStore
import sbt.ProjectRef
import sbt.ClasspathDep
import sbt.util.InterfaceUtil
import xsbti.compile.ExternalHooks
import xsbti.compile.DefaultExternalHooks
import xsbti.compile.ClassFileManager

object ProjectUtils {
  def foldMappers[A](mappers: Seq[A => Option[A]]) = {
    mappers.foldRight((p: A) => p) { (mapper, mappers) => p =>
      mapper(p).getOrElse(mappers(p))
    }
  }

  def toBuildTargetIdentifier(bloopConfig: BloopProjectConfig): BuildTargetIdentifier = {
    val config = bloopConfig.config
    val targetName = config.project.name
    toBuildTargetIdentifier(config.project.directory, targetName)
  }

  def toBuildTargetIdentifier(projectBaseDir: Path, id: String): BuildTargetIdentifier = {
    new BuildTargetIdentifier(toURI(projectBaseDir, id).toString)
  }

  def toURI(projectBaseDir: Path, id: String): URI = {
    // This is the "idiomatic" way of adding a query to a URI in Java
    val existingUri = projectBaseDir.toUri
    new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${id}",
      existingUri.getFragment
    )
  }

  def emptyExternalHooks: ExternalHooks = {
    new DefaultExternalHooks(
      ju.Optional.empty[ExternalHooks.Lookup],
      ju.Optional.empty[ClassFileManager]
    )
  }

  import sbt.internal.inc.MixedAnalyzingCompiler
  val analysisCacheField = MixedAnalyzingCompiler.getClass().getDeclaredField("cache")
  analysisCacheField.setAccessible(true)

  import scala.collection.mutable
  type AnalysisCache = mutable.HashMap[File, java.lang.ref.Reference[AnalysisStore]]
  private val analysisCache: AnalysisCache =
    analysisCacheField.get(MixedAnalyzingCompiler).asInstanceOf[AnalysisCache]

  def bloopStaticCacheStore(analysisOut: File): (BloopAnalysisStore, Boolean) = {
    val analysisStore = new BloopAnalysisStore(FileAnalysisStore.binary(analysisOut))
    analysisCache.synchronized {
      val current = analysisCache.get(analysisOut).flatMap(ref => Option(ref.get))
      current match {
        case Some(current: BloopAnalysisStore) => current -> false
        case _ =>
          analysisCache.put(analysisOut, new SoftReference(analysisStore))
          analysisStore -> true
      }
    }
  }

  final class BloopAnalysisStore(backing: AnalysisStore) extends AnalysisStore {
    private var lastStore: ju.Optional[AnalysisContents] = ju.Optional.empty()
    def readFromDisk: Option[AnalysisContents] = {
      val read = backing.get()
      lastStore.synchronized {
        if (!lastStore.isPresent()) {
          lastStore = read
        }
      }
      InterfaceUtil.toOption(read)
    }
    override def set(analysisFile: AnalysisContents): Unit = ()
    override def get(): ju.Optional[AnalysisContents] = synchronized {
      if (!lastStore.isPresent())
        lastStore = backing.get()
      lastStore
    }
    override def unsafeGet: AnalysisContents = get.get
  }

  def inlinedTask[T](value: T): Def.Initialize[Task[T]] = Def.toITask(Def.value(value))
}
