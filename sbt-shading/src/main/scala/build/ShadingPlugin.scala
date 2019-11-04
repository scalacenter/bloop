package build

import java.io.File

import sbt.Keys._
import sbt.{AutoPlugin, Compile, SettingKey, TaskKey, inConfig, Def, Task}

object BloopShadingPlugin extends AutoPlugin {
  override def trigger = noTrigger

  private val baseSbtConfiguration = Compile
  val Shading = sbt.Configuration.of(
    id = "Shading",
    name = "shading",
    description = "",
    isPublic = false,
    Vector(baseSbtConfiguration),
    transitive = true
  )

  val shadingNamespace = SettingKey[String]("shading-namespace")
  val shadeNamespaces = SettingKey[Set[String]]("shade-namespaces")
  val toShadeJars = TaskKey[Seq[File]]("to-shade-jars")
  val toShadeClasses = TaskKey[Seq[String]]("to-shade-classes")

  object autoImport {

    /** Scope for shading related tasks */
    val Shading = BloopShadingPlugin.Shading

    /** Namespace under which shaded things will be moved */
    val shadingNamespace = BloopShadingPlugin.shadingNamespace

    /**
     * Assume everything under these namespaces is to be shaded.
     *
     * Allows to speed the shading phase, if everything under some namespaces is to be shaded.
     */
    val shadeNamespaces = BloopShadingPlugin.shadeNamespaces
    val toShadeJars = BloopShadingPlugin.toShadeJars
    val toShadeClasses = BloopShadingPlugin.toShadeClasses

    def shadingPackageBin(jar: File, unshadedJars: Seq[File]): Def.Initialize[Task[File]] =
      Def.task {
        val namespace = shadingNamespace.?.value.getOrElse {
          throw new NoSuchElementException("shadingNamespace key not set")
        }

        build.Shading.createPackage(
          jar,
          unshadedJars,
          namespace,
          shadeNamespaces.value,
          toShadeClasses.value,
          toShadeJars.value
        )
      }
  }

  override lazy val buildSettings = super.buildSettings ++ Seq(
    shadeNamespaces := Set()
  )

  override lazy val projectSettings = List(
    // Needs to be customized by project
    toShadeJars := Nil,
    toShadeClasses := {
      build.Shading.toShadeClasses(
        shadeNamespaces.value,
        toShadeJars.value,
        streams.value.log,
        verbose = true
      )
    }
  )
}
