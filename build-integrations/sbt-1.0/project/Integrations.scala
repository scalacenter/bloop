import sbt.{RootProject, uri}

object Integrations {

  val SbtSbt = RootProject(
    uri("git://github.com/scalacenter/sbt.git#fd66dfa1d40a01634f8ce65e299ce7a27cdb247c"))
  val GuardianFrontend = RootProject(
    uri("git://github.com/scalacenter/frontend.git#674c77745cb0d3959a04f90b2d9d57fdb8723c64"))
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
}
