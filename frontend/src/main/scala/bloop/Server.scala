package bloop

sealed abstract class Server

object Server {
  def main(args: Array[String]): Unit =
    Bloop.main(args)
}
