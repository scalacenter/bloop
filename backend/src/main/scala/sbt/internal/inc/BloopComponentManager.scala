/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal
package inc

import java.io.File
import java.util.concurrent.Callable

import sbt.internal.util.FullLogger
import sbt.io.IO
import xsbti._
import xsbti.ArtifactInfo.SbtOrganization

/**
 * A component manager provides access to the pieces of zinc that are distributed as components.
 * Compiler bridge is distributed as a source jar so that it can be compiled against a specific
 * version of Scala.
 *
 * The component manager provides services to install and retrieve components to the local
 * filesystem. This is used for compiled source jars so that the compilation need not be repeated
 * for other projects on the same machine.
 */
class BloopComponentManager(
    globalLock: GlobalLock,
    provider: ComponentProvider,
    secondaryCacheDir: Option[File]
) {

  /** Get all of the files for component 'id', throwing an exception if no files exist for the component. */
  def files(id: String)(ifMissing: IfMissing): Iterable[File] = {
    def notFound = invalid(s"Could not find required component '$id'")
    def getOrElse(orElse: => Iterable[File]): Iterable[File] = {
      val existing = provider.component(id)
      if (existing.isEmpty) orElse else existing
    }

    def createAndCache = {
      ifMissing match {
        case IfMissing.Fail => notFound
        case d: IfMissing.Define =>
          // Run define thunk so that component is saved in primary cache
          d.run()

          // Save from primary cache to secondary cache in case it exists
          if (d.useSecondaryCache) {
            copyFromCacheToSecondaryCache(id)
          }

          getOrElse(notFound)
      }
    }

    def fromSecondary: Iterable[File] = {
      lockSecondaryCache {
        retrieveAndDefineFromSecondaryCache(id)
        getOrElse(createAndCache)
      }.getOrElse(notFound)
    }

    lockLocalCache(getOrElse(fromSecondary))
  }

  /** Get the file for component 'id',
   *  throwing an exception if no files or multiple files exist for the component. */
  def file(id: String)(ifMissing: IfMissing): File = {
    files(id)(ifMissing).toList match {
      case x :: Nil => x
      case xs => invalid(s"Expected single file for component '$id', found: ${xs.mkString(", ")}")
    }
  }

  /** Associate a component id to a series of jars. */
  def define(id: String, files: Iterable[File]): Unit =
    lockLocalCache(provider.defineComponent(id, files.toSeq.toArray))

  private def lockLocalCache[T](action: => T): T = lock(provider.lockFile)(action)
  private def lockSecondaryCache[T](action: => T): Option[T] =
    secondaryCacheDir.map(dir => lock(new File(dir, ".bloop.cache.lock"))(action))

  def lock[T](action: => T): T =
    globalLock(new File(".lock"), new Callable[T] { def call = action })
  private def lock[T](file: File)(action: => T): T =
    globalLock(file, new Callable[T] { def call = action })

  private def invalid(msg: String) = throw new InvalidComponent(msg)

  /** Retrieve the file for component 'id' from the secondary cache. */
  private def retrieveAndDefineFromSecondaryCache(id: String): Unit = {
    secondaryCacheDir.foreach { dir =>
      val file = secondaryCacheFile(id, dir)
      if (file.exists) {
        define(id, Seq(file))
      }
    }
  }

  /** Install the files for component 'id' to the secondary cache. */
  private def copyFromCacheToSecondaryCache(id: String): Unit = {
    val fromPrimaryCache = file(id)(IfMissing.fail)
    secondaryCacheDir.foreach { dir =>
      IO.copyFile(fromPrimaryCache, secondaryCacheFile(id, dir))
    }
  }

  private def secondaryCacheFile(id: String, dir: File): File = {
    new File(new File(dir, "ch.epfl.scala"), s"$id-${BloopComponentManager.stampedVersion}.jar")
  }
}

object BloopComponentManager {
  lazy val (version, timestamp) = {
    val properties = ResourceLoader.getPropertiesFor("/incrementalcompiler.version.properties")
    (properties.getProperty("version"), properties.getProperty("timestamp"))
  }
  lazy val stampedVersion = s"${version}_$timestamp"
}
