package bloop.cli

import utest._

object BloopSpec extends TestSuite {
  val tests = Tests {
    test("lifts -D before the command and keeps the command and its arguments") {
      val (props, rest) =
        Bloop.partitionLauncherProperties(Array("-Dbloop.java-opts=-Xmx8g", "compile", "foo"))
      assert(props == Seq("bloop.java-opts" -> "-Xmx8g"))
      assert(rest.toSeq == Seq("compile", "foo"))
    }

    test("keeps -D after -- untouched (the framework/program owns it)") {
      val cases = Seq(
        Array("test", "foo", "--", "-Dkey=value"),
        Array("run", "app", "--", "-Da=1", "-Db=2")
      )
      cases.foreach { args =>
        val (props, rest) = Bloop.partitionLauncherProperties(args)
        assert(props.isEmpty)
        assert(rest.toSeq == args.toSeq)
      }
    }

    test("lifts -D even when a global option comes first") {
      val (props, rest) =
        Bloop.partitionLauncherProperties(Array("--verbose", "-Dbloop.server=host", "about"))
      assert(props == Seq("bloop.server" -> "host"))
      assert(rest.toSeq == Seq("--verbose", "about"))
    }

    test("lifts -D before a registered command") {
      val (props, rest) = Bloop.partitionLauncherProperties(Array("-Dk=v", "status"))
      assert(props == Seq("k" -> "v"))
      assert(rest.toSeq == Seq("status"))
    }

    test("keeps = signs inside a property value") {
      val (props, _) = Bloop.partitionLauncherProperties(Array("-Dk=a=b", "compile"))
      assert(props == Seq("k" -> "a=b"))
    }

    test("handles a property without a value") {
      val (props, rest) = Bloop.partitionLauncherProperties(Array("-Dflag", "about"))
      assert(props == Seq("flag" -> ""))
      assert(rest.toSeq == Seq("about"))
    }

    test("returns empty for no arguments") {
      val (props, rest) = Bloop.partitionLauncherProperties(Array.empty[String])
      assert(props.isEmpty)
      assert(rest.isEmpty)
    }
  }
}
