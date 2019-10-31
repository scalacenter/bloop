package bloop.bloop4j.api.handlers

import ch.epfl.scala.bsp4j._
import com.google.gson.Gson

abstract class BuildClientHandlers extends BuildClient {
  protected val gson: Gson = new Gson()
}
