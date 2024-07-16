/*
 * Copyright 2011-2024 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.protocol

import java.net.{ Inet4Address, InetAddress, InetSocketAddress }
import javax.net.ssl.KeyManagerFactory

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import io.gatling.commons.validation.Validation
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.filter.{ AllowList, DenyList, Filters }
import io.gatling.core.session._
import io.gatling.core.session.el.El
import io.gatling.http.ResponseTransformer
import io.gatling.http.check.HttpCheck
import io.gatling.http.client.{ Http2PriorKnowledge, Request }
import io.gatling.http.client.realm.Realm
import io.gatling.http.client.uri.Uri
import io.gatling.http.fetch.InferredResourceNaming
import io.gatling.http.request.builder.RequestBuilder
import io.gatling.http.response.Response
import io.gatling.http.util.{ HttpHelper, InetAddresses }

import com.softwaremill.quicklens._
import com.typesafe.scalalogging.LazyLogging
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.ssl.{ OpenSsl, SslProvider }

object HttpProtocolBuilder {
  implicit def toHttpProtocol(builder: HttpProtocolBuilder): HttpProtocol = builder.build

  def apply(configuration: GatlingConfiguration): HttpProtocolBuilder =
    HttpProtocolBuilder(HttpProtocol(configuration), configuration.ssl.useOpenSsl, configuration.ssl.enableSni)
}

final case class HttpProtocolBuilder(protocol: HttpProtocol, useOpenSsl: Boolean, enableSni: Boolean) extends LazyLogging {
  def baseUrl(url: String): HttpProtocolBuilder = baseUrls(List(url))
  def baseUrls(urls: String*): HttpProtocolBuilder = baseUrls(urls.toList)
  def baseUrls(urls: List[String]): HttpProtocolBuilder = this.modify(_.protocol.baseUrls).setTo(urls)
  def warmUp(url: String): HttpProtocolBuilder = this.modify(_.protocol.warmUpUrl).setTo(Some(url))
  def disableWarmUp: HttpProtocolBuilder = this.modify(_.protocol.warmUpUrl).setTo(None)

  // enginePart
  def shareConnections: HttpProtocolBuilder = this.modify(_.protocol.enginePart.shareConnections).setTo(true)
  def localAddress(address: String): HttpProtocolBuilder = localAddresses(address :: Nil)
  def localAddresses(addresses: String*): HttpProtocolBuilder = localAddresses(addresses.toList)
  def localAddresses(addresses: List[String]): HttpProtocolBuilder =
    localAddresses0(addresses.map(InetAddress.getByName))
  def useAllLocalAddresses: HttpProtocolBuilder = useAllLocalAddressesMatching()
  def useAllLocalAddressesMatching(patterns: String*): HttpProtocolBuilder = {
    val compiledPatterns = patterns.map(_.r.pattern)

    def filter(addresses: List[InetAddress]): List[InetAddress] =
      if (compiledPatterns.isEmpty) {
        addresses
      } else {
        addresses.filter { address =>
          val hostAddress = address.getHostAddress
          compiledPatterns.exists(_.matcher(hostAddress).matches)
        }
      }

    localAddresses0(filter(InetAddresses.AllLocalAddresses))
  }

  private def localAddresses0(localAddresses: List[InetAddress]): HttpProtocolBuilder =
    this
      .modify(_.protocol.enginePart.localAddresses)
      .setTo(localAddresses)

  def maxConnectionsPerHost(max: Int): HttpProtocolBuilder = this.modify(_.protocol.enginePart.maxConnectionsPerHost).setTo(max)
  def perUserKeyManagerFactory(f: Long => KeyManagerFactory): HttpProtocolBuilder = this.modify(_.protocol.enginePart.perUserKeyManagerFactory).setTo(Some(f))

  // requestPart
  def disableAutoReferer: HttpProtocolBuilder = this.modify(_.protocol.requestPart.autoReferer).setTo(false)
  def disableAutoOrigin: HttpProtocolBuilder = this.modify(_.protocol.requestPart.autoOrigin).setTo(false)
  def disableCaching: HttpProtocolBuilder = this.modify(_.protocol.requestPart.cache).setTo(false)
  def header(name: CharSequence, value: Expression[String]): HttpProtocolBuilder = this.modify(_.protocol.requestPart.headers)(_ + (name -> value))
  def headers(headers: Map[_ <: CharSequence, String]): HttpProtocolBuilder =
    this.modify(_.protocol.requestPart.headers)(_ ++ headers.view.mapValues(_.el[String]))
  def acceptHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.ACCEPT, value)
  def acceptCharsetHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.ACCEPT_CHARSET, value)
  def acceptEncodingHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.ACCEPT_ENCODING, value)
  def acceptLanguageHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.ACCEPT_LANGUAGE, value)
  def authorizationHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.AUTHORIZATION, value)
  def connectionHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.CONNECTION, value)
  def contentTypeHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.CONTENT_TYPE, value)
  def doNotTrackHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.DNT, value)
  def originHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.ORIGIN, value)
  def userAgentHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.USER_AGENT, value)
  def upgradeInsecureRequestsHeader(value: Expression[String]): HttpProtocolBuilder = header(HttpHeaderNames.UPGRADE_INSECURE_REQUESTS, value)
  def basicAuth(username: Expression[String], password: Expression[String]): HttpProtocolBuilder = authRealm(HttpHelper.buildBasicAuthRealm(username, password))
  def digestAuth(username: Expression[String], password: Expression[String]): HttpProtocolBuilder =
    authRealm(HttpHelper.buildDigestAuthRealm(username, password))
  private def authRealm(realm: Expression[Realm]): HttpProtocolBuilder = this.modify(_.protocol.requestPart.realm).setTo(Some(realm))
  def silentResources: HttpProtocolBuilder = this.modify(_.protocol.requestPart.silentResources).setTo(true)
  def silentUri(pattern: String): HttpProtocolBuilder = this.modify(_.protocol.requestPart.silentUri).setTo(Some(pattern.r.pattern))
  def disableUrlEncoding: HttpProtocolBuilder = this.modify(_.protocol.requestPart.disableUrlEncoding).setTo(true)
  def sign(calculator: (Request, Session) => Validation[Request]): HttpProtocolBuilder =
    this.modify(_.protocol.requestPart.signatureCalculator).setTo(Some(calculator))
  def signWithOAuth1(
      consumerKey: Expression[String],
      clientSharedSecret: Expression[String],
      token: Expression[String],
      tokenSecret: Expression[String]
  ): HttpProtocolBuilder =
    signWithOAuth1(consumerKey, clientSharedSecret, token, tokenSecret, useAuthorizationHeader = true)
  def signWithOAuth1(
      consumerKey: Expression[String],
      clientSharedSecret: Expression[String],
      token: Expression[String],
      tokenSecret: Expression[String],
      useAuthorizationHeader: Boolean
  ): HttpProtocolBuilder =
    sign(RequestBuilder.oauth1SignatureCalculator(consumerKey, clientSharedSecret, token, tokenSecret, useAuthorizationHeader = useAuthorizationHeader))
  def enableHttp2: HttpProtocolBuilder = {
    require(enableSni, "SNI is disabled in configuration so HTTP/2 can't work.")
    if (useOpenSsl) {
      if (SslProvider.isAlpnSupported(SslProvider.OPENSSL_REFCNT)) {
        this.modify(_.protocol.enginePart.enableHttp2).setTo(true)
      } else {
        throw new UnsupportedOperationException(
          s"BoringSSL is enabled and supported on your platform but your version ${OpenSsl.versionString} doesn't support ALPN so you can't use HTTP/2."
        )
      }
    } else if (SslProvider.isAlpnSupported(SslProvider.JDK)) {
      this.modify(_.protocol.enginePart.enableHttp2).setTo(true)
    } else {
      throw new UnsupportedOperationException(s"Your Java version ${sys.props("java.version")} doesn't support ALPN so you can't use HTTP/2.")
    }
  }

  def http2PriorKnowledge(remotes: Map[String, Boolean]): HttpProtocolBuilder =
    this
      .modify(_.protocol.enginePart.http2PriorKnowledge)
      .setTo(remotes.map { case (address, isHttp2) =>
        val remote = address.split(':') match {
          case Array(hostname, port) => new Remote(hostname, port.toInt)
          case Array(hostname)       => new Remote(hostname, 443)
          case _                     => throw new IllegalArgumentException("Invalid address for HTTP/2 prior knowledge: " + address)
        }
        remote -> (if (isHttp2) Http2PriorKnowledge.HTTP2_SUPPORTED else Http2PriorKnowledge.HTTP1_ONLY)
      })

  // responsePart
  def disableFollowRedirect: HttpProtocolBuilder = this.modify(_.protocol.responsePart.followRedirect).setTo(false)
  def maxRedirects(max: Int): HttpProtocolBuilder = this.modify(_.protocol.responsePart.maxRedirects).setTo(max)
  def strict302Handling: HttpProtocolBuilder = this.modify(_.protocol.responsePart.strict302Handling).setTo(true)
  def redirectNamingStrategy(f: (Uri, String, Int) => String): HttpProtocolBuilder = this.modify(_.protocol.responsePart.redirectNamingStrategy).setTo(f)
  def transformResponse(responseTransformer: ResponseTransformer): HttpProtocolBuilder =
    this.modify(_.protocol.responsePart.responseTransformer).setTo(Some(responseTransformer))
  def check(checks: HttpCheck*): HttpProtocolBuilder = this.modify(_.protocol.responsePart.checks)(_ ::: checks.toList)
  def checkIf(condition: Expression[Boolean])(thenChecks: HttpCheck*): HttpProtocolBuilder =
    check(thenChecks.map(_.checkIf(condition)): _*)
  def checkIf(condition: (Response, Session) => Validation[Boolean])(thenChecks: HttpCheck*): HttpProtocolBuilder =
    check(thenChecks.map(_.checkIf(condition)): _*)
  def inferHtmlResources(): HttpProtocolBuilder = inferHtmlResources(None)
  def inferHtmlResources(allow: AllowList): HttpProtocolBuilder = inferHtmlResources(Some(new Filters(allow, DenyList.Empty)))
  def inferHtmlResources(allow: AllowList, deny: DenyList): HttpProtocolBuilder = inferHtmlResources(Some(new Filters(allow, deny)))
  def inferHtmlResources(deny: DenyList): HttpProtocolBuilder = inferHtmlResources(Some(new Filters(deny, AllowList.Empty)))
  private def inferHtmlResources(filters: Option[Filters]): HttpProtocolBuilder =
    this
      .modify(_.protocol.responsePart.inferHtmlResources)
      .setTo(true)
      .modify(_.protocol.responsePart.htmlResourcesInferringFilters)
      .setTo(filters)
  def nameInferredHtmlResourcesAfterUrlTail: HttpProtocolBuilder = nameInferredHtmlResources(InferredResourceNaming.UrlTailInferredResourceNaming)
  def nameInferredHtmlResourcesAfterAbsoluteUrl: HttpProtocolBuilder = nameInferredHtmlResources(InferredResourceNaming.AbsoluteUrlInferredResourceNaming)
  def nameInferredHtmlResourcesAfterRelativeUrl: HttpProtocolBuilder = nameInferredHtmlResources(InferredResourceNaming.RelativeUrlInferredResourceNaming)
  def nameInferredHtmlResourcesAfterPath: HttpProtocolBuilder = nameInferredHtmlResources(InferredResourceNaming.PathInferredResourceNaming)
  def nameInferredHtmlResourcesAfterLastPathElement: HttpProtocolBuilder =
    nameInferredHtmlResources(InferredResourceNaming.LastPathElementInferredResourceNaming)
  def nameInferredHtmlResources(f: Uri => String): HttpProtocolBuilder = this.modify(_.protocol.responsePart.inferredHtmlResourcesNaming).setTo(f)

  // wsPart
  def wsBaseUrl(url: String): HttpProtocolBuilder = wsBaseUrls(List(url))
  def wsBaseUrls(urls: String*): HttpProtocolBuilder = wsBaseUrls(urls.toList)
  def wsBaseUrls(urls: List[String]): HttpProtocolBuilder = this.modify(_.protocol.wsPart.wsBaseUrls).setTo(urls)
  def wsReconnect: HttpProtocolBuilder = wsMaxReconnects(Int.MaxValue)
  def wsMaxReconnects(max: Int): HttpProtocolBuilder = this.modify(_.protocol.wsPart.maxReconnects).setTo(max)
  def wsAutoReplyTextFrame(f: PartialFunction[String, String]): HttpProtocolBuilder =
    this.modify(_.protocol.wsPart.autoReplyTextFrames).setTo(f.lift)
  def wsAutoReplySocketIo4: HttpProtocolBuilder = wsAutoReplyTextFrame { case "2" => "3" }
  def wsUnmatchedInboundMessageBufferSize(max: Int): HttpProtocolBuilder =
    this.modify(_.protocol.wsPart.unmatchedInboundMessageBufferSize).setTo(max)

  // ssePart
  def sseUnmatchedInboundMessageBufferSize(max: Int): HttpProtocolBuilder =
    this.modify(_.protocol.ssePart.unmatchedInboundMessageBufferSize).setTo(max)

  // proxyPart
  def noProxyFor(hosts: String*): HttpProtocolBuilder = this.modify(_.protocol.proxyPart.proxyExceptions).setTo(hosts)
  def proxy(proxy: Proxy): HttpProtocolBuilder = this.modify(_.protocol.proxyPart.proxy).setTo(Some(proxy.proxyServer))

  // dnsPart
  def asyncNameResolution(dnsServers: String*): HttpProtocolBuilder =
    asyncNameResolution(dnsServers.map { dnsServer =>
      dnsServer.split(':') match {
        case Array(hostname, port) => new InetSocketAddress(hostname, port.toInt)
        case Array(hostname)       => new InetSocketAddress(hostname, 53)
        case _                     => throw new IllegalArgumentException("Invalid dnsServer: " + dnsServer)
      }
    }.toArray)
  def asyncNameResolution(dnsServers: Array[InetSocketAddress]): HttpProtocolBuilder =
    this.modify(_.protocol.dnsPart.dnsNameResolution).setTo(AsyncDnsNameResolution(dnsServers))
  def hostNameAliases(aliases: Map[String, List[String]]): HttpProtocolBuilder = {
    val aliasesToInetAddresses = aliases.map { case (hostname, ips) =>
      hostname -> ips.map(ip => InetAddress.getByAddress(hostname, InetAddress.getByName(ip).getAddress)).asJava
    }
    this.modify(_.protocol.dnsPart.hostNameAliases).setTo(aliasesToInetAddresses)
  }
  def perUserNameResolution: HttpProtocolBuilder =
    this.modify(_.protocol.dnsPart.perUserNameResolution).setTo(true)

  private def preResolve(baseUrl: String, aliasedHostnames: Set[String]): Unit =
    try {
      val uri = Uri.create(baseUrl)
      if (!aliasedHostnames.contains(uri.getHost)) {
        InetAddress.getAllByName(uri.getHost)
      }
    } catch {
      case NonFatal(e) =>
        logger.debug(s"Couldn't pre-resolve hostname from baseUrl $baseUrl", e)
    }

  def build: HttpProtocol = {
    if (protocol.proxyPart.proxy.isEmpty) {
      val aliasedHostnames = protocol.dnsPart.hostNameAliases.keySet
      protocol.baseUrls.foreach(preResolve(_, aliasedHostnames))
      protocol.wsPart.wsBaseUrls.foreach(preResolve(_, aliasedHostnames))
    }

    protocol
  }
}
