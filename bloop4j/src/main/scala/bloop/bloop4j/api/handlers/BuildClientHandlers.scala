package bloop.bloop4j.api.handlers

import ch.epfl.scala.bsp4j._
import com.google.gson.{Gson, JsonElement}

import scala.collection.mutable.ListBuffer

abstract class BuildClientHandlers extends BuildClient {
  protected val gson: Gson = new Gson()

  def parseAs[T: Class](obj: Object): T = {
    val json = obj.asInstanceOf[JsonElement]
    gson.fromJson[T](json, implicitly[Class[T]])
  }
}
