name := "with-tests"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.13.4" % "test"

libraryDependencies += "org.specs2" %% "specs2-core" % "4.0.0" % "test"

libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test"
testFrameworks += new TestFramework("utest.runner.Framework")
