addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % sys.props.apply("plugin.version"))

resolvers += Resolver.url("Triplequote Plugins Releases",
  url("https://repo.triplequote.com/artifactory/sbt-plugins-release/"))(Resolver.ivyStylePatterns)
addSbtPlugin("com.triplequote" % "sbt-hydra" % "2.2.2")

