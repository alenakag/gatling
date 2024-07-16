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

package io.gatling.http.request.builder

import java.{ util => ju }
import java.nio.charset.Charset

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import io.gatling.commons.util.Throwables._
import io.gatling.commons.validation._
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session._
import io.gatling.http.auth.DigestAuthSupport
import io.gatling.http.cache.{ BaseUrlSupport, HttpCaches, LocalAddressSupport }
import io.gatling.http.client.{ Request, RequestBuilder => ClientRequestBuilder }
import io.gatling.http.client.realm.DigestRealm
import io.gatling.http.client.uri.{ Uri, UriEncoder }
import io.gatling.http.cookie.CookieSupport
import io.gatling.http.protocol.HttpProtocol
import io.gatling.http.referer.RefererHandling
import io.gatling.http.util.HttpHelper

import com.typesafe.scalalogging.LazyLogging
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.util.AsciiString

object RequestExpressionBuilder {
  private val BuildRequestErrorMapper: String => String = "Failed to build request: " + _

  private def mergeCaseInsensitive[T](left: Map[CharSequence, T], right: Map[CharSequence, T]): Map[CharSequence, T] =
    right.foldLeft(left) { case (acc, (key, value)) =>
      val targetKey = acc.keySet.find(_.toString.equalsIgnoreCase(key.toString)).getOrElse(key)
      acc.updated(targetKey, value)
    }
}

abstract class RequestExpressionBuilder(
    commonAttributes: CommonAttributes,
    httpCaches: HttpCaches,
    httpProtocol: HttpProtocol,
    configuration: GatlingConfiguration
) extends LazyLogging {
  import RequestExpressionBuilder._

  protected val charset: Charset = configuration.core.charset
  protected val headers: Map[CharSequence, Expression[String]] = {
    val rawHeaders =
      if (commonAttributes.ignoreProtocolHeaders) {
        mergeCaseInsensitive(Map.empty, commonAttributes.headers)
      } else {
        val uniqueProtocolHeaders = mergeCaseInsensitive(Map.empty, httpProtocol.requestPart.headers)
        mergeCaseInsensitive(uniqueProtocolHeaders, commonAttributes.headers)
      }

    configureHeaders(rawHeaders)
  }

  protected def configureHeaders(rawHeaders: Map[CharSequence, Expression[String]]): Map[CharSequence, Expression[String]] =
    rawHeaders

  private val refererHeaderIsUndefined: Boolean = !headers.keys.exists(AsciiString.contentEqualsIgnoreCase(_, HttpHeaderNames.REFERER))
  private val fixUrlEncoding: Boolean = !commonAttributes.disableUrlEncoding.getOrElse(httpProtocol.requestPart.disableUrlEncoding)

  private val baseUrl: Session => Option[String] = protocolBaseUrl

  protected def protocolBaseUrl: Session => Option[String] =
    BaseUrlSupport.httpBaseUrl(httpProtocol)

  protected def protocolBaseUrls: List[String] =
    httpProtocol.baseUrls

  protected def isAbsoluteUrl(url: String): Boolean =
    HttpHelper.isAbsoluteHttpUrl(url)

  private def resolveRelativeAgainstBaseUrl(relativeUrl: String, baseUrl: Option[String]): Validation[Uri] =
    baseUrl match {
      case Some(base) =>
        val fullUrl = base + relativeUrl
        try {
          Uri.create(fullUrl).success
        } catch {
          // don't use safe in order to save lambda instances
          case NonFatal(e) => s"url $fullUrl can't be parsed into an Uri: ${e.rootMessage}".failure
        }
      case _ => s"No baseUrl defined but provided url is relative : $relativeUrl".failure
    }

  private def makeAbsolute(session: Session, url: String): Validation[Uri] =
    if (isAbsoluteUrl(url))
      Uri.create(url).success
    else
      resolveRelativeAgainstBaseUrl(url, baseUrl(session))

  private val buildURI: Expression[Uri] = {
    val queryParams = commonAttributes.queryParams

    commonAttributes.urlOrURI match {
      case Left(StaticValueExpression(staticUrl)) if (protocolBaseUrls.sizeIs <= 1 || isAbsoluteUrl(staticUrl)) && queryParams.isEmpty =>
        if (isAbsoluteUrl(staticUrl)) {
          UriEncoder.uriEncoder(fixUrlEncoding).encode(Uri.create(staticUrl), ju.Collections.emptyList()).expressionSuccess
        } else {
          val uriV = resolveRelativeAgainstBaseUrl(staticUrl, protocolBaseUrls.headOption)
            .map(uri => UriEncoder.uriEncoder(fixUrlEncoding).encode(uri, ju.Collections.emptyList()))
          _ => uriV
        }

      case Left(url) =>
        // url is not static, or multiple baseUrl, or queryParams
        session =>
          for {
            resolvedUrl <- url(session)
            absoluteUri <- makeAbsolute(session, resolvedUrl)
            resolvedQueryParams <- resolveParamJList(queryParams, session)
          } yield UriEncoder.uriEncoder(fixUrlEncoding).encode(absoluteUri, resolvedQueryParams)

      case Right(uri) =>
        uri.expressionSuccess
    }
  }

  private val maybeProxy = commonAttributes.proxy.orElse(httpProtocol.proxyPart.proxy)
  private def configureProxy(requestBuilder: ClientRequestBuilder): Unit =
    maybeProxy.foreach { proxy =>
      if (!httpProtocol.proxyPart.proxyExceptions.contains(requestBuilder.getUri.getHost)) {
        requestBuilder.setProxyServer(proxy)
      }
    }

  private def configureCookies(session: Session, requestBuilder: ClientRequestBuilder): Unit = {
    val cookies = CookieSupport.getStoredCookies(session, requestBuilder.getUri)
    if (cookies.nonEmpty) {
      requestBuilder.setCookies(cookies.asJava)
    }
  }

  private val addRefererHeader = httpProtocol.requestPart.autoReferer && refererHeaderIsUndefined
  private val (staticHeaders, dynamicHeaders) = headers.toArray.partitionMap {
    case (key, StaticValueExpression(value)) => Left(key -> value)
    case other                               => Right(other)
  }
  private def configureHeaders(session: Session, requestBuilder: ClientRequestBuilder): Validation[_] = {
    staticHeaders.foreach { case (key, value) => requestBuilder.addHeader(key, value) }
    if (addRefererHeader) {
      RefererHandling.getStoredReferer(session).foreach(requestBuilder.addHeader(HttpHeaderNames.REFERER, _))
    }
    if (dynamicHeaders.isEmpty) {
      Validation.unit
    } else {
      dynamicHeaders.foldLeft(requestBuilder.success) { case (requestBuilder, (key, value)) =>
        for {
          rb <- requestBuilder
          value <- value(session)
        } yield rb.addHeader(key, value)
      }
    }
  }

  private val maybeRealm = commonAttributes.realm.orElse(httpProtocol.requestPart.realm)
  private def configureRealm(session: Session, requestBuilder: ClientRequestBuilder): Validation[_] =
    maybeRealm match {
      case Some(realm) =>
        realm(session).map {
          case digestRealm: DigestRealm =>
            requestBuilder.setRealm(DigestAuthSupport.realmWithAuthorizationGen(session, digestRealm))
          case other => requestBuilder.setRealm(other)
        }
      case _ => Validation.unit
    }

  private val hasLocalAddresses = httpProtocol.enginePart.localAddresses.nonEmpty
  private def configureLocalAddress(session: Session, requestBuilder: ClientRequestBuilder): Unit =
    if (hasLocalAddresses) {
      LocalAddressSupport.localAddresses(session).foreach(requestBuilder.setLocalAddresses)
    }

  private val maybeSignatureCalculator: Option[(Request, Session) => Validation[Request]] =
    commonAttributes.signatureCalculator.orElse(httpProtocol.requestPart.signatureCalculator)
  private def configureSignatureCalculator(session: Session, requestBuilder: ClientRequestBuilder): Unit =
    maybeSignatureCalculator match {
      case Some(signatureCalculator) =>
        requestBuilder.setSignatureCalculator { request =>
          signatureCalculator(request, session) match {
            case Failure(message) => throw new IllegalArgumentException(s"Failed to compute signature: $message")
            case Success(signed)  => signed
          }
        }
      case _ =>
    }

  protected def configureRequestTimeout(requestBuilder: ClientRequestBuilder): Unit

  protected def configureProtocolSpecific(session: Session, requestBuilder: ClientRequestBuilder): Validation[_]

  def build: Expression[Request] =
    session =>
      safely(BuildRequestErrorMapper) {
        for {
          uri <- buildURI(session)
          requestName <- commonAttributes.requestName(session)
          nameResolver <- httpCaches.nameResolver(session) // note: DNS cache is supposed to be set early

          requestBuilder = {
            val rb = new ClientRequestBuilder(requestName, commonAttributes.method, uri, nameResolver)
              .setDefaultCharset(charset)
              .setAutoOrigin(httpProtocol.requestPart.autoOrigin)

            configureProxy(rb)
            configureRequestTimeout(rb)
            configureCookies(session, rb)
            configureLocalAddress(session, rb)
            configureSignatureCalculator(session, rb)
            rb
          }

          _ <- configureHeaders(session, requestBuilder)
          _ <- configureRealm(session, requestBuilder)
          _ <- configureProtocolSpecific(session, requestBuilder)
        } yield requestBuilder.build
      }
}
