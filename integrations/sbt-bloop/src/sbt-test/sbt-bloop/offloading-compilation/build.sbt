lazy val foo = taskKey[String]("foo")
lazy val p1: Project = project.settings(
  /*
  bloopCompile in Compile := {
    println("compile in p1")
    sbt.internal.inc.Analysis.Empty
  }
  */
)

lazy val p2: Project = project.dependsOn(p1).settings(
  bloopInternalClasspath in Compile := {
    //(bloopInternalClasspath in Compile).dependsOn(compile in Compile in p1).value
    (bloopInternalClasspath in Compile).value
  },
  /*
  bloopCompile in Compile := {
    Def
      .task({ println("compile in p2 "); sbt.internal.inc.Analysis.Empty })
      .dependsOn(
        (bloopInternalClasspath in Compile)
      )
      .value
  }
  */
)

/*
lazy val foo = taskKey[String]("foo")
lazy val p1: Project = project.settings()

lazy val p2: Project = project.settings(
  bloopInternalClasspath in Compile := {
    (bloopInternalClasspath in Compile).dependsOn(compile in Compile in p1).value
  },
  foo := Def.taskDyn {
    println("Hello i am dynamic")
    Def
      .task {
        val ref = Keys.thisProjectRef.value
        println(s"Hello i am static in ${ref}")
        ""
      }
      .apply { t =>
        println(s"i am ${t.hashCode()}")
        t
      }
  }.value
)
 */

/*
lazy val foo = taskKey[String]("foo")
//lazy val bar = taskKey[String]("bar")
lazy val p1: Project = project.settings(
  bloopCompile in Compile := {
    println("bloopCompile in p1")
    sbt.internal.inc.Analysis.empty
  },
  foo in Compile := {
    (bloopCompile in Compile).value
    ""
  }
)

lazy val p2: Project = project.settings(
  bloopCompile in Compile := {
    sbt.internal.inc.Analysis.empty
  },
  bloopCompile in Compile := {
    (bloopCompile in Compile).dependsOn(foo in Compile in p1).value
  }
)
 */
