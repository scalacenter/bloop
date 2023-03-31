package bloop.rifle

import bloop.rifle.VersionUtil.{jvmRelease, parseBloopAbout, parseJavaVersion}
import com.eed3si9n.expecty.Expecty.expect

class ParsingTests extends munit.FunSuite {

  implicit class BV(s: String) {
    implicit def v: BloopVersion                              = BloopVersion(s)
    implicit def p: Option[BloopRifle.BloopServerRuntimeInfo] = parseBloopAbout(s)
    implicit def j: Option[Int]                               = jvmRelease(s)
    implicit def jv: Option[Int]                              = parseJavaVersion(s)
  }

  test("bloop version comparisons test") {
    expect("1.4.9".v isOlderThan "1.4.10".v)
    expect("1.4.9".v isOlderThan "1.4.9-22".v)
    expect("1.4.9-22".v isOlderThan "1.4.10".v)
    expect(!("1.4.9".v isOlderThan "1.4.9".v))
    expect("1.4.10-2".v isOlderThan "1.4.10-4".v)
    expect("1.4.10-2-abc".v isOlderThan "1.4.10-4-def".v)
  }

  test("jvm release parsing test") {
    expect("1.8".j == Some(8))
    expect("1.8.75".j == Some(8))
    expect("1.8.64_3".j == Some(8))
    expect("1.8_3".j == Some(8))
    expect("9".j == Some(9))
    expect("14".j == Some(14))
    expect("17".j == Some(17))
  }

  test("parse jvm version") {
    expect("""openjdk version "1.8.0_292" """.jv == Some(8))
    expect("""openjdk version "9" """.jv == Some(9))
    expect("""openjdk version "11.0.11" 2021-04-20 """.jv == Some(11))
    expect("""openjdk version "16" 2021-03-16 """.jv == Some(16))
  }

  val jreBloopOutput =
    """|bloop v1.4.11
       |
       |Using Scala v2.12.8 and Zinc v1.3.0-M4+47-d881fa2f
       |Running on Java JRE v11.0.13 (/usr/local/openjdk-11)
       |  -> Doesn't support debugging user code, runtime doesn't implement Java Debug Interface (JDI).
       |Maintained by the Scala Center and the community.
       |""".stripMargin

  val jdkBloopOutput =
    """|bloop v1.4.11
       |
       |Using Scala v2.12.8 and Zinc v1.3.0-M4+47-d881fa2f
       |Running on Java JDK v16.0.2 (/usr/lib/jvm/java-16-openjdk-amd64)
       |  -> Supports debugging user code, Java Debug Interface (JDI) is available.
       |Maintained by the Scala Center and the community.""".stripMargin

  test("parse jre bloop about") {
    expect(jreBloopOutput.p == Some(BloopRifle.BloopServerRuntimeInfo(
      BloopVersion("1.4.11"),
      11,
      "/usr/local/openjdk-11"
    )))
  }

  test("parse jdk bloop about") {
    expect(jdkBloopOutput.p == Some(BloopRifle.BloopServerRuntimeInfo(
      BloopVersion("1.4.11"),
      16,
      "/usr/lib/jvm/java-16-openjdk-amd64"
    )))
  }

}
