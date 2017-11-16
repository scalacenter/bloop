object Dependencies {
  val zincVersion       = "1.0.2"
  val coursierVersion   = "1.0.0-RC8"
  val lmVersion         = "1.0.0"
  val configDirsVersion = "5"
  val caseAppVersion    = "1.2.0-M4"

  import sbt.librarymanagement.syntax.stringToOrganization
  val configDirectories = "io.github.soc"              % "directories"            % configDirsVersion
  val libraryManagement = "org.scala-sbt"              %% "librarymanagement-ivy" % lmVersion
  val coursier          = "io.get-coursier"            %% "coursier"              % coursierVersion
  val coursierCache     = "io.get-coursier"            %% "coursier-cache"        % coursierVersion
  val caseApp           = "com.github.alexarchambault" %% "case-app"              % caseAppVersion
}
