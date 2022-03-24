package bloop.integrations.sbt

import java.io.File

import sbt.SettingKey
import sbt.TaskKey

object ScalaNativeKeys {
  import sbt.{settingKey, taskKey}
  val nativeClang: TaskKey[File] = taskKey[File]("Location of the clang compiler.")
  val nativeClangPP: TaskKey[File] = taskKey[File]("Location of the clang++ compiler.")
  val nativeLinkStubs: SettingKey[Boolean] =
    settingKey[Boolean]("Whether to link `@stub` methods, or ignore them.")
  val nativeLink: TaskKey[File] = taskKey[File]("Generates native binary without running it.")
  val nativeMode: SettingKey[String] =
    settingKey[String]("Compilation mode, either \"debug\" or \"release\".")
  val nativeGC: SettingKey[String] =
    settingKey[String]("GC choice, either \"none\", \"boehm\" or \"immix\".")

  val nativeCompileOptions: TaskKey[Seq[String]] =
    taskKey[Seq[String]]("Additional options are passed to clang during compilation.")
  val nativeLinkingOptions: TaskKey[Seq[String]] =
    taskKey[Seq[String]]("Additional options that are passed to clang during linking.")
}
