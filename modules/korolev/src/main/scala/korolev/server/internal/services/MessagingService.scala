/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package korolev.server.internal.services

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets
import java.util.zip.{Deflater, Inflater}
import korolev.Qsid
import korolev.data.{Bytes, BytesLike}
import korolev.effect.{Effect, Queue, Reporter, Stream}
import korolev.effect.syntax.*
import korolev.internal.Frontend
import korolev.server.{HttpResponse, WebSocketResponse}
import korolev.server.DeflateCompressionService
import korolev.server.internal.HttpResponse
import korolev.web.Request.Head
import korolev.web.Response
import korolev.web.Response.Status
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer

private[korolev] final class MessagingService[F[_]: Effect](
  reporter: Reporter,
  commonService: CommonService[F],
  sessionsService: SessionsService[F, _, _],
  compressionSupport: Option[DeflateCompressionService[F]]
) {

  import MessagingService._

  /**
   * Poll message from session's ongoing queue.
   */
  def longPollingSubscribe(qsid: Qsid, rh: Head): F[HttpResponse[F]] =
    for {
      _        <- sessionsService.createAppIfNeeded(qsid, rh, createTopic(qsid))
      maybeApp <- sessionsService.getApp(qsid)
      // See webSocketMessaging()
      maybeMessage <- maybeApp.fold(SomeReloadMessageF)(_.frontend.outgoingMessages.pull())
      response <- maybeMessage match {
                    case None => Effect[F].pure(commonGoneResponse)
                    case Some(message) =>
                      HttpResponse(
                        status = Response.Status.Ok,
                        message = message,
                        headers = commonResponseHeaders
                      )
                  }
    } yield {
      response
    }

  /**
   * Push message to session's incoming queue.
   */
  def longPollingPublish(qsid: Qsid, data: Stream[F, Bytes]): F[HttpResponse[F]] =
    for {
      topic   <- takeTopic(qsid)
      message <- data.fold(Bytes.empty)(_ ++ _).map(_.asUtf8String)
      _       <- topic.enqueue(message)
    } yield commonOkResponse

  private lazy val inflaters = ThreadLocal.withInitial(() => new Inflater(true))
  private lazy val deflaters = ThreadLocal.withInitial(() => new Deflater(Deflater.DEFAULT_COMPRESSION, true))

  private lazy val wsJsonDeflateDecoder = (bytes: Bytes) => {
    val inflater = inflaters.get()
    val inputArray = bytes.asArray

    val chunkSize = 1024 // Choose a reasonable size
    val outputBlocks = new ListBuffer[Array[Byte]]()
    var totalBytesInflated = 0

    inflater.reset()
    inflater.setInput(inputArray)

    while (!inflater.finished()) {
      val outputArray = new Array[Byte](chunkSize)
      val bytesInflated = inflater.inflate(outputArray)
      totalBytesInflated += bytesInflated

      // Store the block even if partially filled
      outputBlocks += java.util.Arrays.copyOf(outputArray, bytesInflated)
    }

    // Concatenate all the blocks to form the final decompressed string
    val finalOutputArray = outputBlocks.flatten.toArray
    new String(finalOutputArray, 0, totalBytesInflated, StandardCharsets.UTF_8)
  }

  private lazy val wsJsonDeflateEncoder = (message: String) => {
    val encoder = StandardCharsets.UTF_8.newEncoder()
    val deflater = deflaters.get()

    // Initialize input as a byte array from the string
    val inputArray = message.getBytes(StandardCharsets.UTF_8)

    // Clear and reset deflater
    deflater.reset()

    // Set the input data for the deflater
    deflater.setInput(inputArray)
    deflater.finish()

    // Initialize a byte array for output
    val outputArray = new Array[Byte](inputArray.length)

    // Temporary buffer to hold deflation result
    val tempOutputArray = new Array[Byte](1024)

    // Initialize a ListBuffer to hold multiple output blocks
    var outputBlocks = ListBuffer[Array[Byte]]()

    // Deflate the input in chunks
    while (!deflater.finished()) {
      val bytesDeflated = deflater.deflate(tempOutputArray)
      outputBlocks += java.util.Arrays.copyOf(tempOutputArray, bytesDeflated)
    }

    // Concatenate all blocks to form the final array
    val compressedArray = outputBlocks.flatten.toArray

    // Convert it back to korolev.data.Bytes
    Bytes.wrap(compressedArray)
  }

  private val defaultDecoder = (bytes: Bytes) => Effect[F].pure(bytes.asUtf8String)
  private val defaultEncoder = (message: String) => Effect[F].pure(BytesLike[Bytes].utf8(message))

  def webSocketMessaging(
    qsid: Qsid,
    rh: Head,
    incomingMessages: Stream[F, Bytes],
    protocols: Seq[String]
  ): F[WebSocketResponse[F]] = {
    val (selectedProtocol, decoder, encoder) = {
      // Support for protocol compression. A client can tell us
      // it can decompress the messages.
      if (protocols.contains(ProtocolJsonDeflate)) {
        compressionSupport match {
          case Some(DeflateCompressionService(decoder, encoder)) => 
            (ProtocolJsonDeflate, decoder, encoder)
          case None => 
            (ProtocolJsonDeflate, defaultDecoder, defaultEncoder)
        }
      } else {
        (ProtocolJson, defaultDecoder, defaultEncoder)
      }
    }
    sessionsService.createAppIfNeeded(qsid, rh, incomingMessages.mapAsync(decoder)) flatMap { _ =>
      sessionsService.getApp(qsid) flatMap {
        case Some(app) =>
          val httpResponse = Response(Status.Ok, app.frontend.outgoingMessages.mapAsync(encoder), Nil, None)
          Effect[F].pure(WebSocketResponse(httpResponse, selectedProtocol))
        case None =>
          // Respond with reload message because app was not found.
          // In this case it means that server had ben restarted and
          // do not have an information about the state which had been
          // applied to render of the page on a client side.
          Stream(Frontend.ReloadMessage).mat().map { messages =>
            val httpResponse = Response(Status.Ok, messages.mapAsync(encoder), Nil, None)
            WebSocketResponse(httpResponse, selectedProtocol)
          }
      }
    }
  }

  /**
   * Sessions created via long polling subscription takes messages from topics
   * stored in this table.
   */
  private val longPollingTopics = TrieMap.empty[Qsid, Queue[F, String]]

  /**
   * Same headers in all responses
   */
  private val commonResponseHeaders = Seq(
    "cache-control" -> "no-cache",
    "content-type"  -> "application/json"
  )

  /**
   * Same response for all 'publish' requests.
   */
  private val commonOkResponse = Response(
    status = Response.Status.Ok,
    body = Stream.empty[F, Bytes],
    headers = commonResponseHeaders,
    contentLength = Some(0L)
  )

  /**
   * Same response for all 'subscribe' requests where outgoing stream is
   * consumed.
   */
  private val commonGoneResponse = Response(
    status = Response.Status.Gone,
    body = Stream.empty[F, Bytes],
    headers = commonResponseHeaders,
    contentLength = Some(0L)
  )

  private def takeTopic(qsid: Qsid) =
    Effect[F].delay {
      if (longPollingTopics.contains(qsid)) longPollingTopics(qsid)
      else throw new Exception(s"There is no long-polling topic matching $qsid")
    }

  private def createTopic(qsid: Qsid) = {
    reporter.debug(s"Create long-polling topic for $qsid")
    val topic = Queue[F, String]()
    topic.cancelSignal.runAsync(_ => longPollingTopics.remove(qsid))
    longPollingTopics.putIfAbsent(qsid, topic)
    topic.stream
  }
}

private[korolev] object MessagingService {

  private val ProtocolJsonDeflate = "json-deflate"
  private val ProtocolJson        = "json"

  def SomeReloadMessageF[F[_]: Effect]: F[Option[String]] =
    Effect[F].pure(Option(Frontend.ReloadMessage))
}
