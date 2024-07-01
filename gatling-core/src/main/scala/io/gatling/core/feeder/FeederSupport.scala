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

package io.gatling.core.feeder

import java.io.{ BufferedInputStream, BufferedOutputStream, File, FileNotFoundException, FileOutputStream }
import java.net.URI

import scala.collection.immutable.ArraySeq
import scala.util.Using

import io.gatling.commons.validation._
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.feeder.SeparatedValuesParser._
import io.gatling.core.json.JsonParsers
import io.gatling.core.util.ResourceCache

trait FeederSupport extends ResourceCache {
  implicit def seq2FeederBuilder[T](data: IndexedSeq[Map[String, T]])(implicit configuration: GatlingConfiguration): FeederBuilderBase[T] =
    SourceFeederBuilder(InMemoryFeederSource(data, "in-memory"), configuration)
  implicit def array2FeederBuilder[T](data: Array[Map[String, T]])(implicit configuration: GatlingConfiguration): FeederBuilderBase[T] =
    SourceFeederBuilder(InMemoryFeederSource(ArraySeq.unsafeWrapArray(data), "in-memory"), configuration)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def csv(filePath: String, quoteChar: Char = DefaultQuoteChar)(implicit configuration: GatlingConfiguration): BatchableFeederBuilder[String] =
    separatedValues(filePath, CommaSeparator, quoteChar)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def ssv(filePath: String, quoteChar: Char = DefaultQuoteChar)(implicit configuration: GatlingConfiguration): BatchableFeederBuilder[String] =
    separatedValues(filePath, SemicolonSeparator, quoteChar)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def tsv(filePath: String, quoteChar: Char = DefaultQuoteChar)(implicit configuration: GatlingConfiguration): BatchableFeederBuilder[String] =
    separatedValues(filePath, TabulationSeparator, quoteChar)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def separatedValues(filePath: String, separator: Char, quoteChar: Char = DefaultQuoteChar)(implicit
      configuration: GatlingConfiguration
  ): BatchableFeederBuilder[String] =
    cachedResource(filePath) match {
      case Success(resource) => SourceFeederBuilder[String](new SeparatedValuesFeederSource(resource, separator, quoteChar), configuration)
      case Failure(message)  => throw new FileNotFoundException(s"Could not locate feeder file: $message")
    }

  def jsonFile(filePath: String)(implicit jsonParsers: JsonParsers, configuration: GatlingConfiguration): FileBasedFeederBuilder[Any] =
    cachedResource(filePath) match {
      case Success(resource) => SourceFeederBuilder(new JsonFileFeederSource(resource, jsonParsers), configuration)
      case Failure(message)  => throw new FileNotFoundException(s"Could not locate feeder file: $message")
    }

  def jsonUrl(url: String)(implicit jsonParsers: JsonParsers, configuration: GatlingConfiguration): FeederBuilderBase[Any] = {
    val tempFile = File.createTempFile("jsonUrl", null)
    tempFile.deleteOnExit()

    Using.resources(new BufferedInputStream(new URI(url).toURL.openStream), new BufferedOutputStream(new FileOutputStream(tempFile))) { (is, os) =>
      is.transferTo(os)
    }

    jsonFile(tempFile.getPath)
  }
}
