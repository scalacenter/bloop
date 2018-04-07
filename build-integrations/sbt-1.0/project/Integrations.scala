import sbt.{RootProject, uri}

object Integrations {

  val SbtSbt = RootProject(
    uri("git://github.com/scalacenter/sbt.git#fd66dfa1d40a01634f8ce65e299ce7a27cdb247c"))
  val GuardianFrontend = RootProject(
    uri("git://github.com/scalacenter/frontend.git#fd8da1929d8a3bd39ca6027ffba6c0850e036ce3"))
  val MiniBetterFiles = RootProject(uri(
    "git://github.com/scalacenter/mini-better-files.git#0ed848993a2fd5a36e4366b5efb9c68dce958fc2"))
  val WithResources = RootProject(
    uri("git://github.com/scalacenter/with-resources.git#f0a46830cae7ef6282d9bba64b6da34bae18f339"))
  val WithTests = RootProject(
    uri("git://github.com/scalacenter/with-tests.git#3be26f4f21427c5bc0b83deb96d6e66973102eb2"))

}
