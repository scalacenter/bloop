package bloop

import bloop.io.AbsolutePath

case class JavaInstance(javaHome: AbsolutePath, options: Seq[String] = Seq.empty)

object JavaInstance {
  val Default = JavaInstance(AbsolutePath(
    sys.props.get("bloop.compilation.java-home") getOrElse sys.props("java.home")))
}
