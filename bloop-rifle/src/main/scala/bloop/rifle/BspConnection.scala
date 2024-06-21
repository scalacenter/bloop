package bloop.rifle

import java.net.Socket

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait BspConnection {
  def address: String
  def openSocket(period: FiniteDuration, timeout: FiniteDuration): Socket
  def closed: Future[Int]
  def stop(): Unit
}
