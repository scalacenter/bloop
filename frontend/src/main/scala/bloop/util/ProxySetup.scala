package bloop.util

import java.net.URI
import java.net.MalformedURLException

import bloop.logging.Logger
import _root_.monix.execution.atomic.{Atomic, AtomicAny}

import scala.util.Try
import scala.collection.JavaConverters._

object ProxySetup {
  private case class ProxySettings(host: String, port: Int, nonProxyHosts: Option[String])

  /** Build map of startup proxy settings set via Java System properties in the server. */
  private val configuredProxySettings: AtomicAny[Map[Proxy, ProxySettings]] = {
    def getJvmProxyProperties(proxy: Proxy): Option[ProxySettings] = {
      def sysprop(key: String): Option[String] = Option(System.getProperty(key))
      val maybeVarHost = sysprop(proxy.propertyNameHost)
      val maybeVarPort = sysprop(proxy.propertyNamePort).flatMap(prop => Try(prop.toInt).toOption)
      val maybeNoProxy = proxy.propertyNameNoProxyHosts.flatMap(p => sysprop(p))
      maybeVarHost.map { host =>
        ProxySettings(host, maybeVarPort.getOrElse(proxy.defaultPort), maybeNoProxy)
      }
    }

    lazy val env = System.getenv().asScala.toMap
    Atomic {
      Proxy.supportedProxySettings.flatMap { p =>
        getJvmProxyProperties(p)
          .orElse(getEnvProxyProperties(env, p.envVar, None))
          .map(p -> _)
      }.toMap
    }
  }

  sealed trait Proxy {
    val propertyNameHost: String
    val propertyNamePort: String
    val propertyNameNoProxyHosts: Option[String]
    val defaultPort: Int
    val envVar: String
  }

  object Proxy {
    final case object HttpProxy extends Proxy {
      val propertyNameHost = "http.proxyHost"
      val propertyNamePort = "http.proxyPort"
      val propertyNameNoProxyHosts = Some("http.nonProxyHosts")
      val defaultPort = 80
      val envVar = "http_proxy"
    }

    final case object FtpProxy extends Proxy {
      val propertyNameHost = "ftp.proxyHost"
      val propertyNamePort = "ftp.proxyPort"
      val propertyNameNoProxyHosts = Some("ftp.nonProxyHosts")
      val defaultPort = 80
      val envVar = "ftp_proxy"
    }

    final case object HttpsProxy extends Proxy {
      val propertyNameHost = "https.proxyHost"
      val propertyNamePort = "https.proxyPort"
      val propertyNameNoProxyHosts = None
      val defaultPort = 443
      val envVar = "https_proxy"
    }

    final case object SocksProxy extends Proxy {
      val propertyNameHost = "socksProxyHost"
      val propertyNamePort = "socksProxyHost"
      val propertyNameNoProxyHosts = None
      val defaultPort = 1080
      val envVar = "socks_proxy"
    }

    val supportedProxySettings: Seq[Proxy] = List(HttpProxy, FtpProxy, HttpsProxy, SocksProxy)
  }

  /** Forces the initialization of environment variables, called in `Server`. */
  def init(): Unit = ()

  /**
   * Update proxy settings per client environment variables.
   *
   * A bloop client can change its environment variables to modify the proxy settings
   * globally in the bloop server. However, there is no mechanism to clean proxy
   * settings. Users that want to do so must shut down the server and start it again.
   * This policy is taken to avoid bad interactions with clients that run on different
   * environments where proxy settings are not set (e.g. inside editors like emacs/vscode).
   *
   * The update process affects all the current bloop clients. Unfortunately, there is no
   * way to mitigate this behavior because ivy/coursier/etc do not expose interfaces to
   * configure the proxy settings directly. If users define different proxy settings in
   * different clients, bloop will have an undefined behavior. Note that users are entitled
   * to have a mix of clients with no proxy settings and with the same proxy settings,
   * in which case bloop will consistently use the proxy settings set by one of the configured
   * clients.
   *
   * Whenever remote compilation is added, the handling of environment variables must
   * be disabled as it's unsafe (it's the only scenario where it's legit to have different
   * proxy settings).
   *
   * Ref https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html
   *
   * @param environment The environment of the bloop client.
   * @param logger A logger where we give feedback to the user.
   */
  def updateProxySettings(environment: Map[String, String], logger: Logger): Unit = {
    Proxy.supportedProxySettings.foreach { proxy =>
      val settingsFromEnvironment =
        getEnvProxyProperties(environment, proxy.envVar, Some(logger))
      settingsFromEnvironment.foreach { proxySettings =>
        configuredProxySettings.transform { settingsMap =>
          settingsMap.get(proxy) match {
            // Do nothing if the settings are the same, otherwise update globally
            case Some(setSettings) if proxySettings == setSettings => settingsMap
            case _ =>
              System.setProperty(proxy.propertyNameHost, proxySettings.host)
              System.setProperty(proxy.propertyNamePort, proxySettings.port.toString())
              proxy.propertyNameNoProxyHosts match {
                case None => ()
                case Some(k) =>
                  proxySettings.nonProxyHosts match {
                    case Some(v) => System.setProperty(k, v)
                    case None => System.clearProperty(k)
                  }
              }
              settingsMap + (proxy -> proxySettings)
          }
        }
      }
    }
  }

  /** Populate proxy settings from the environment variables. */
  private def getEnvProxyProperties(
      env: Map[String, String],
      key: String,
      logger: Option[Logger]
  ): Option[ProxySettings] = {
    env.get(key).flatMap { proxyVar =>
      try {
        val url = new URI(proxyVar)
        val maybeNoProxy = env.get("no_proxy").map(_.replace(',', '|'))
        Some(ProxySettings(url.getHost(), url.getPort(), maybeNoProxy))
      } catch {
        case e: MalformedURLException =>
          logger.foreach(_.warn(s"Expected valid URI format in proxy value of $proxyVar"))
          None
      }
    }
  }
}
