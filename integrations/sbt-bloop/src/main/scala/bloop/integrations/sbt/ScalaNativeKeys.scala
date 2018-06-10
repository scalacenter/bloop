package bloop.integrations.sbt

import java.io.File

object ScalaNativeKeys {
  import sbt.{settingKey, taskKey}
  val nativeClang = taskKey[File]("Location of the clang compiler.")
  val nativeClangPP = taskKey[File]("Location of the clang++ compiler.")
  val nativeLinkStubs = settingKey[Boolean]("Whether to link `@stub` methods, or ignore them.")
  val nativeLink = taskKey[File]("Generates native binary without running it.")
  val nativeMode = settingKey[String]("Compilation mode, either \"debug\" or \"release\".")
  val nativeGC = settingKey[String]("GC choice, either \"none\", \"boehm\" or \"immix\".")

  val nativeCompileOptions =
    taskKey[Seq[String]]("Additional options are passed to clang during compilation.")
  val nativeLinkingOptions =
    taskKey[Seq[String]]("Additional options that are passed to clang during linking.")
}
