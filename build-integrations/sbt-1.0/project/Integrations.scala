import sbt.{RootProject, uri}

object Integrations {

  val SbtSbt = RootProject(
    uri("git://github.com/scalacenter/sbt.git#c84db3a3969e269aeed0e9d9f2f384a0b029b82c"))
  val GuardianFrontend = RootProject(
    uri("git://github.com/scalacenter/frontend.git#fd8da1929d8a3bd39ca6027ffba6c0850e036ce3"))
  val MiniBetterFiles = RootProject(uri(
    "git://github.com/scalacenter/mini-better-files.git#0ed848993a2fd5a36e4366b5efb9c68dce958fc2"))
  val WithResources = RootProject(
    uri("git://github.com/scalacenter/with-resources.git#7529b2c3ac455cbb1889d4791c4e0d4957e29306"))
  val WithTests = RootProject(
    uri("git://github.com/scalacenter/with-tests.git#a8169f081240627e3adb1651d1680f1df46ecd39"))

}
