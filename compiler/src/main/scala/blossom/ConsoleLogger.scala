package blossom

import java.util.function.Supplier

import xsbti.Logger
import sbt.util.InterfaceUtil.toSupplier

object QuietLogger extends Logger {
  override def debug(msg: Supplier[String]): Unit =
    ()

  override def error(msg: Supplier[String]): Unit =
    println(msg.get())

  override def info(msg: Supplier[String]): Unit =
    ()

  override def warn(msg: Supplier[String]): Unit =
    ()

  override def trace(msg: Supplier[Throwable]): Unit =
    ()
}

object ConsoleLogger extends Logger {
  override def debug(msg: Supplier[String]): Unit =
    println(msg.get())

  override def error(msg: Supplier[String]): Unit =
    println(msg.get())

  override def info(msg: Supplier[String]): Unit =
    println(msg.get())

  override def warn(msg: Supplier[String]): Unit =
    println(msg.get())

  override def trace(msg: Supplier[Throwable]): Unit =
    ()
}
