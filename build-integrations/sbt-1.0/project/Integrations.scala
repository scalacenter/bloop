import sbt.{RootProject, uri}

object Integrations {

  val SbtSbt = RootProject(
    uri("git://github.com/scalacenter/sbt.git#9d60c11e6bbd09f4b7e3976a7bc4a5888f96ca37"))
  val GuardianFrontend = RootProject(
    uri("git://github.com/scalacenter/frontend.git#fd8da1929d8a3bd39ca6027ffba6c0850e036ce3"))
  val GuardianGrid = RootProject(
    uri("git://github.com/guardian/grid.git#b27abb5f060c599b20fa058361ef9574f9f22285"))
  val MiniBetterFiles = RootProject(uri(
    "git://github.com/scalacenter/mini-better-files.git#0ed848993a2fd5a36e4366b5efb9c68dce958fc2"))
  val WithResources = RootProject(
    uri("git://github.com/scalacenter/with-resources.git#f0a46830cae7ef6282d9bba64b6da34bae18f339"))
  val WithTests = RootProject(
    uri("git://github.com/scalacenter/with-tests.git#3be26f4f21427c5bc0b83deb96d6e66973102eb2"))
  val AkkaAkka = RootProject(
    uri("git://github.com/scalacenter/akka.git#ad1c3fcad5f5521792f3772a195b0b9167f570fd"))
  val CrossPlatform = RootProject(
    uri("git://github.com/scalacenter/cross-platform.git#6a29444158ec6b3de5384f0d49a3d9cded32b818"))
  val Finagle = RootProject(
    uri("git://github.com/jvican/finagle.git#aa67a73baf34c4ae4c7843442b707b2bd67c2705"))
  val Scalatra = RootProject(
    uri("git://github.com/scalatra/scalatra.git#575d51ae93bbb6add7f546373fe78766c03b40af"))
  val Algebird = RootProject(
    uri("git://github.com/jvican/algebird.git#33a9540790a992222156f83b984759360e196a9b"))
}
