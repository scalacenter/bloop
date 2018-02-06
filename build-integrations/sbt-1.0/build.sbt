val SbtSbt = RootProject(
  uri("git://github.com/sbt/sbt.git#f07434a3eb1f0c78c6cf8a62b27ee2a55c63a35a"))
val GuardianFrontend = RootProject(
  uri("git://github.com/guardian/frontend.git#c6611d93635133a8a0282c4ffc8d304effdca374"))
val MiniBetterFiles = RootProject(
  uri(
    "git://github.com/scalacenter/mini-better-files.git#0ed848993a2fd5a36e4366b5efb9c68dce958fc2"))
val WithResources = RootProject(
  uri("git://github.com/scalacenter/with-resources.git#7529b2c3ac455cbb1889d4791c4e0d4957e29306"))
val WithTests = RootProject(
  uri("git://github.com/scalacenter/with-tests.git#7a0c7f7d38efd53ca9ec3a347df3638932bd619e"))

val integrations = List(SbtSbt, GuardianFrontend, MiniBetterFiles, WithResources, WithTests)

val dummy = project
  .in(file("."))
  .aggregate(integrations: _*)
