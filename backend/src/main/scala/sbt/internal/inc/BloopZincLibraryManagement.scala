/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal.inc

import java.io.File
import java.net.URLClassLoader

import scala.concurrent.ExecutionContext

import sbt.internal.inc.classpath.ClassLoaderCache
import sbt.librarymanagement.ModuleID
import xsbti._
import xsbti.compile._

object BloopZincLibraryManagement {
  import xsbti.compile.ScalaInstance

  /**
   * Instantiate a Scala compiler that is instrumented to analyze dependencies.
   * This Scala compiler is useful to create your own instance of incremental
   * compilation.
   */
  def scalaCompiler(
      scalaInstance: ScalaInstance,
      globalLock: GlobalLock,
      componentProvider: ComponentProvider,
      secondaryCacheDir: Option[File],
      compilerBridgeSource: ModuleID,
      scalaJarsTarget: File,
      logger: _root_.bloop.logging.Logger,
      scheduler: ExecutionContext
  ): AnalyzingCompiler = {
    val compilerBridgeProvider = BloopComponentCompiler.interfaceProvider(
      compilerBridgeSource,
      new BloopComponentManager(globalLock, componentProvider, secondaryCacheDir),
      scalaJarsTarget,
      logger,
      scheduler
    )
    val loader = Some(new ClassLoaderCache(new URLClassLoader(new Array(0))))
    new AnalyzingCompiler(
      scalaInstance,
      compilerBridgeProvider,
      ClasspathOptionsUtil.boot(),
      _ => (),
      loader
    )
  }
}
