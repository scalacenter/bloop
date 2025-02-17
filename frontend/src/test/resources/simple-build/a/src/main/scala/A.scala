trait A {
  def hello(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    println(s"Hello from ${file.value}:${line.value}")
  }
}
