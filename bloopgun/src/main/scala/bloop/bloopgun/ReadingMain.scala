package bloop.bloopgun

import java.{util => ju}

object ReadingMain extends App {
  /*
  val x = readInt()
  val y = readInt()
  println(x + y)
   */
  /*
  val s = new ju.Scanner(System.in)
  println("Waiting for line")
  println(s.nextLine())
   */
  val bytes = new Array[Byte](1024)
  var read = System.in.read(bytes)
  var exit: Boolean = false
  while (!exit && read != -1) {
    val msg = new String(bytes, 0, read)
    if (msg == "exit") {
      exit = true
    } else {
      System.out.write(bytes, 0, read)
      read = System.in.read(bytes)
    }
  }
}
