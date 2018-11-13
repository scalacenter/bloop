package bloop.util

import java.net.URL
import java.net.MalformedURLException

import scala.collection.JavaConverters._
import bloop.engine.State
import bloop.logging.Logger
import monix.execution.atomic.AtomicAny

import scala.util.Try

object ProxyEnvironment {

  private val startupSettings = AtomicAny[Map[Proxy,ProxySettings]](startup())

  /**
   * Extract environments variables from the given state to set the proxy properties of the jvm.
   * If environment variable are not found in the state, the startup properties are used.
   * If the property is still no found in startup properties the corresponding property is cleared
   * to let user add and remove env var.
   * This function will mutate the gobal jvm properties, consequently it's do not support concurent client with differents environments.
   * Information came from  https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html
   * */
  def setJvmProxySettings(state: State): Unit = {

    val map: Map[String, String] = state.commonOptions.env.stringPropertyNames().asScala.map(prop => prop ->  state.commonOptions.env.getProperty(prop)).toMap

    val logger = state.logger

    Proxy.values.foreach{ proxy =>
      //get proxy settings from state environment if there is not proxy settings in state use the settings gift at server startup
      val maybeSettings = getEnvProxyProperties(map, proxy.envVar, Some(logger)) orElse startupSettings.get.get(proxy)
      maybeSettings match {
        case Some(settings) => setGlobalProxyProperties(proxy, settings)
        case None => clearGlobalProxyProperties(proxy)
      }
    }
  }



  private def setGlobalProxyProperties(proxy : Proxy , settings : ProxySettings): Unit = {
    System.setProperty(proxy.propertyNameHost, settings.host)
    System.setProperty(proxy.propertyNamePort, settings.port.toString())
    for {
      propName <- proxy.propertyNameNoProxy
      noProxy <- settings.noProxy
    } yield {
      System.setProperty(propName, noProxy)
    }
    ()
  }

  private def clearGlobalProxyProperties(proxy : Proxy): Unit = {
    System.clearProperty(proxy.propertyNameHost)
    System.clearProperty(proxy.propertyNamePort)
    if (proxy.propertyNameNoProxy.isEmpty)
      proxy.propertyNameNoProxy.foreach(System.clearProperty)
  }


  /** Extract ProxySettings from map */
  private def getEnvProxyProperties(
                                        env: collection.Map[String, String],
                                        envVar: String,
                                        logger : Option[Logger]
                                       ): Option[ProxySettings] = {

    val maybeNoProxy = env.get("no_proxy").map(_.replace(',', '|'))

    env.get(envVar).flatMap{ proxyVar =>
      try {

        val url = new URL(proxyVar)
        Some(ProxySettings(url.getHost(),url.getPort(),maybeNoProxy))
      } catch {
        case e: MalformedURLException =>
          logger.foreach(_.warn(s"Unexpected non-URI format in proxy value of $proxyVar"))
          None
      }
    }
  }

  /** The goal of this function is to extract proxy settings that was set via jvm -D parameters */
  private def getJvmProxyProperties(proxy : Proxy) : Option[ProxySettings]  = {
    val maybeVarHost = Option(System.getProperties.getProperty(proxy.propertyNameHost))
    val maybeVarPort = Option(System.getProperties.getProperty(proxy.propertyNamePort)).flatMap(prop => Try(prop.toInt).toOption)
    val maybeNoProxy = proxy.propertyNameNoProxy.flatMap(prop => Option(System.getProperties.getProperty(prop)))
    maybeVarHost.map{ host =>
      ProxySettings(host, maybeVarPort.getOrElse(proxy.defaultPort), maybeNoProxy)
    }
  }

  /* Build the map containing the server startup proxy settings. jvm -D parameters are favoured over environment variables */
  private def startup(): Map[Proxy, ProxySettings] = {
    val env = System.getenv().asScala
    Proxy.values.flatMap{ pro =>
      getJvmProxyProperties(pro).orElse(getEnvProxyProperties(env, pro.envVar, None)).map(pro -> _)
    }.toMap
  }

  /**
    * Use server startup proxy settings to parametrise the jvm properties
    */
  def init() : Unit = {
    startupSettings.get.foreach(x => setGlobalProxyProperties(x._1, x._2))
  }

}

sealed trait Proxy {
  val propertyNameHost : String
  val propertyNamePort : String
  val propertyNameNoProxy : Option[String]
  val defaultPort : Int
  val envVar : String
}
case object HttpProxy extends Proxy {
  val propertyNameHost  = "http.proxyHost"
  val propertyNamePort  = "http.proxyPort"
  val propertyNameNoProxy = Some("http.nonProxyHosts")
  val defaultPort = 80
  val envVar = "http_proxy"
}
case object FtpProxy extends Proxy {
  val propertyNameHost  = "ftp.proxyHost"
  val propertyNamePort  = "ftp.proxyPort"
  val propertyNameNoProxy  = Some("ftp.nonProxyHosts")
  val defaultPort = 80
  val envVar = "ftp_proxy"
}
case object HttpsProxy extends Proxy {
  val propertyNameHost  = "https.proxyHost"
  val propertyNamePort  = "https.proxyPort"
  val propertyNameNoProxy  = None
  val defaultPort = 443
  val envVar = "https_proxy"
}
case object SocksProxy extends Proxy{
  val propertyNameHost = "socksProxyHost"
  val propertyNamePort = "socksProxyHost"
  val propertyNameNoProxy = None
  val defaultPort = 1080
  val envVar = "socks_proxy"
}
object Proxy{
  val values = List(HttpProxy,FtpProxy, HttpsProxy,SocksProxy)
}

//Should we fantomize this ?
case class ProxySettings(host: String, port : Int, noProxy : Option[String])
