package org.pantsbuild.jarjar

// from https://github.com/sbt/sbt-assembly/blob/17786404117889e5a8225c97b9b7639160fb91e8/src/main/scala/org/pantsbuild/jarjar/JJProcessor.scala

import org.pantsbuild.jarjar.util.{EntryStruct, JarProcessor}

import java.lang.invoke.{MethodHandles, MethodType, MethodHandle}

import scala.collection.JavaConverters._

class JarJarProcessor(val proc: JarProcessor) {
  def process(entry: EntryStruct): Boolean = proc.process(entry)

  def getExcludes(): Set[String] = {
    val field = proc.getClass().getDeclaredField("kp")
    field.setAccessible(true)
    val keepProcessor = field.get(proc)

    if (keepProcessor == null) Set()
    else {
      val method = proc.getClass().getDeclaredMethod("getExcludes")
      method.setAccessible(true)
      method.invoke(proc).asInstanceOf[java.util.Set[String]].asScala.toSet
    }
  }

}

object JarJarProcessor {
  def apply(
      patterns: Seq[PatternElement],
      verbose: Boolean,
      skipManifest: Boolean
  ): JarJarProcessor = {
    // Lots of magic foo because sbt throws IllegalAccessError when accessing
    // MainProcessor even it's defined in the same package :/

    val mainProcessorClz = getClass.getClassLoader.loadClass("org.pantsbuild.jarjar.MainProcessor")
    val ctor = mainProcessorClz.getDeclaredConstructor(
      classOf[java.util.List[PatternElement]],
      classOf[Boolean],
      classOf[Boolean]
    )
    ctor.setAccessible(true)
    val mainP = ctor
      .newInstance(patterns.asJava, (verbose: java.lang.Boolean), (skipManifest: java.lang.Boolean))
      .asInstanceOf[org.pantsbuild.jarjar.util.JarProcessor]

    new JarJarProcessor(mainP)
  }
}
