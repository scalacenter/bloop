package object buildpress {
  import java.io.PrintStream
  // Define print and println here to shadow those coming from predef
  def print(out: PrintStream, msg: String): Unit = out.print(msg)
  def println(out: PrintStream, msg: String): Unit = out.println(msg)
}
