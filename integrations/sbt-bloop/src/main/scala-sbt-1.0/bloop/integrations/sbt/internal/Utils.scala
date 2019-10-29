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

object Utils {
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

  import sbt.internal.inc.MixedAnalyzingCompiler
  val analysisCacheField = MixedAnalyzingCompiler.getClass().getDeclaredField("cache")
  analysisCacheField.setAccessible(true)

  import scala.collection.mutable
  type AnalysisCache = mutable.HashMap[File, java.lang.ref.Reference[AnalysisStore]]
  private val analysisCache: AnalysisCache =
    analysisCacheField.get(MixedAnalyzingCompiler).asInstanceOf[AnalysisCache]

  def bloopStaticCacheStore(analysisOut: File): BloopAnalysisStore = {
    val analysisStore = new BloopAnalysisStore(FileAnalysisStore.binary(analysisOut))
    //analysisStore.readFromDisk
    analysisCache.synchronized {
      val current = analysisCache.get(analysisOut).flatMap(ref => Option(ref.get))
      current match {
        case Some(current: BloopAnalysisStore) => current
        case _ =>
          println(s"replacing cache store $current by our own store for $analysisOut")
          analysisCache.put(analysisOut, new SoftReference(analysisStore)); analysisStore
      }
    }
  }

  final class BloopAnalysisStore(backing: AnalysisStore) extends AnalysisStore {
    private var lastStore: ju.Optional[AnalysisContents] = ju.Optional.empty()
    def readFromDisk: Option[AnalysisContents] = {
      val read = backing.get()
      //println(s"Reading from disk $read")
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
  }

  def inlinedTask[T](value: T): Def.Initialize[Task[T]] = Def.toITask(Def.value(value))
}
