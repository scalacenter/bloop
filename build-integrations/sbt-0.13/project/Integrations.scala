import sbt.{RootProject, uri}

object Integrations {

  val ApacheSpark = RootProject(
    uri("git://github.com/scalacenter/spark.git#e7ac5e9fa8ca3eeb4fecdbbfbe745ef71e276498"))
  val LihaoyiUtest = RootProject(
    uri("git://github.com/scalacenter/utest.git#dbe6a554d838c4b4b5997702bfa7791f60ca02ef"))
  val ScalaScala = RootProject(
    uri("git://github.com/scalacenter/scala.git#430deb1f3ff23ae0876434b302808d7b004337f6"))
  val ScalaCenterVersions = RootProject(
    uri("git://github.com/scalacenter/versions.git#c296028a33b06ba3a41d399d77c21f6b7100c001"))
  val Scalameta = RootProject(
    uri("git://github.com/scalacenter/scalameta.git#653a0cc3d4d5bf615c6586fade94b5b78c3dd5ba"))

}
