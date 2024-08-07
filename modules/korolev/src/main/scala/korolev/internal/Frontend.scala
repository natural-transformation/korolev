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

package korolev.internal

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import korolev.Context.FileHandler
import korolev.Metrics
import korolev.Metrics
import korolev.data.Bytes
import korolev.effect.{AsyncTable, Effect, Queue, Reporter, Stream}
import korolev.effect.syntax._
import korolev.web.{FormData, PathAndQuery}
import levsha.Id
import levsha.events.EventId
import levsha.impl.DiffRenderContext.ChangesPerformer
import scala.annotation.switch
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/**
 * Typed interface to client side
 */
final class Frontend[F[_]: Effect](incomingMessages: Stream[F, String], heartbeatLimit: Option[Int])(implicit
  reporter: Reporter,
  ec: ExecutionContext
) {

  import Frontend._

  private val lastDescriptor            = new AtomicInteger(0)
  private val avgDiffTime               = new AtomicLong(0)
  private val remoteDomChangesPerformer = new RemoteDomChangesPerformer()

  private val customCallbacks  = mutable.Map.empty[String, String => F[Unit]]
  private val downloadFiles    = mutable.Map.empty[String, DownloadFileMeta[F]]
  private val stringPromises   = AsyncTable.unsafeCreateEmpty[F, String, String]
  private val formDataPromises = AsyncTable.unsafeCreateEmpty[F, String, FormData]
  private val filesPromises    = AsyncTable.unsafeCreateEmpty[F, String, Stream[F, Bytes]]

  // Store file name and it`s length as data
  private val fileNamePromises = AsyncTable.unsafeCreateEmpty[F, String, List[(String, Long)]]

  private val outgoingQueue = Queue[F, String]()

  private val List(rawDomEvents, rawBrowserHistoryChanges, rawClientMessages) = incomingMessages
    .map(parseMessage)
    .sort(3) {
      case (CallbackType.DomEvent.code, _) => 0
      case (CallbackType.History.code, _)  => 1
      case _                               => 2
    }

  val outgoingMessages: Stream[F, String] = outgoingQueue.stream

  val domEventMessages: Stream[F, Frontend.DomEventMessage] =
    rawDomEvents.map { case (_, args) =>
      val Array(eventCounter, target, tpe) = args.split(':')
      DomEventMessage(eventCounter.toInt, Id(target), tpe)
    }

  val browserHistoryMessages: Stream[F, PathAndQuery] =
    rawBrowserHistoryChanges.map { case (_, args) =>
      PathAndQuery.fromString(args)
    }

  private def sendRaw(str: String) =
    outgoingQueue.enqueue(str)

  private def send(args: Any*): F[Unit] = {

    val sb = new mutable.StringBuilder()
    sb.append('[')
    args.foreach {
      case s: String =>
        sb.append('"')
        jsonEscape(sb, s, unicode = true)
        sb.append('"')
        sb.append(',')
      case x =>
        sb.append(x.toString)
        sb.append(',')
    }
    sb.update(sb.length - 1, ' ') // replace last comma to space
    sb.append(']')
    sendRaw(sb.result())
  }

  def listenEvent(name: String, preventDefault: Boolean): F[Unit] =
    send(Procedure.ListenEvent.code, name, preventDefault)

  def uploadForm(id: Id): F[FormData] =
    for {
      descriptor <- nextDescriptor()
      _          <- send(Procedure.UploadForm.code, id.mkString, descriptor)
      result     <- formDataPromises.get(descriptor)
    } yield result

  def listFiles(id: Id): F[List[(String, Long)]] =
    for {
      descriptor <- nextDescriptor()
      _          <- send(Procedure.ListFiles.code, id.mkString, descriptor)
      files      <- fileNamePromises.get(descriptor)
    } yield files

  def uploadFile(id: Id, handler: FileHandler): F[Stream[F, Bytes]] =
    for {
      descriptor <- nextDescriptor()
      _          <- send(Procedure.UploadFile.code, id.mkString, descriptor, handler.fileName)
      file       <- filesPromises.get(descriptor)
    } yield file

  def downloadFile(name: String, stream: Stream[F, Bytes], size: Option[Long], mimeType: String): F[Unit] = {
    val (consumed, updatedStream) = stream.handleConsumed
    val id                        = lastDescriptor.getAndIncrement().toString
    consumed.runAsync(_ => downloadFiles.remove(name))
    downloadFiles.put(id, DownloadFileMeta(updatedStream, size, mimeType))
    send(Procedure.DownloadFile.code, id, name)
  }

  def resolveFileDownload(descriptor: String): F[Option[DownloadFileMeta[F]]] =
    Effect[F].delay(downloadFiles.get(descriptor))

  def focus(id: Id): F[Unit] =
    send(Procedure.Focus.code, id.mkString)

  private def nextDescriptor() =
    Effect[F].delay(lastDescriptor.getAndIncrement().toString)

  def extractProperty(id: Id, name: String): F[String] =
    for {
      descriptor <- nextDescriptor()
      _          <- send(Procedure.ExtractProperty.code, descriptor, id.mkString, name)
      result     <- stringPromises.get(descriptor)
    } yield result

  def setProperty(id: Id, name: String, value: Any): F[Unit] =
    send(Procedure.ModifyDom.code, ModifyDomProcedure.SetAttr.code, id.mkString, 0, name, value, true)

  def evalJs(code: String): F[String] =
    for {
      descriptor <- nextDescriptor()
      _          <- send(Procedure.EvalJs.code, descriptor, code)
      result     <- stringPromises.get(descriptor)
    } yield result

  def resetForm(id: Id): F[Unit] =
    send(Procedure.RestForm.code, id.mkString)

  def changePageUrl(pq: PathAndQuery): F[Unit] =
    send(Procedure.ChangePageUrl.code, pq.mkString)

  def setEventCounter(id: Id, eventType: String, n: Int): F[Unit] =
    send(Procedure.SetEventCounter.code, id.mkString, eventType, n)

  def resetEventCounters(): F[Unit] =
    send(Procedure.ResetEventCounters.code)

  def reload(): F[Unit] =
    send(Procedure.Reload.code)

  def reloadCss(): F[Unit] =
    send(Procedure.ReloadCss.code)

  def extractEventData(dem: DomEventMessage): F[String] =
    for {
      descriptor <- nextDescriptor()
      _          <- send(Procedure.ExtractEventData.code, descriptor, dem.target.mkString, dem.eventType)
      result     <- stringPromises.get(descriptor)
    } yield result

  def performDomChanges(f: ChangesPerformer => Unit): F[Unit] = {
    def diff = {
      val timeStart = System.nanoTime()
      val sb        = remoteDomChangesPerformer.buffer
      sb.append('[')
      sb.append(Procedure.ModifyDom.codeString)
      sb.append(',')
      f(remoteDomChangesPerformer)
      sb.update(sb.length - 1, ' ') // replace last comma to space
      sb.append(']')
      val result    = remoteDomChangesPerformer.buffer.result()
      val timeEnd   = System.nanoTime()
      val timeTotal = timeEnd - timeStart
      Metrics.MaxDiffNanos.update(prev => Math.max(prev, timeTotal))
      Metrics.MinDiffNanos.update(prev => if (prev == 0) timeTotal else Math.min(prev, timeTotal))
      avgDiffTime.set((avgDiffTime.get + timeTotal) / 2)
      result
    }
    for {
      // Switch to blocking context if rendering is slow
      result <- if (avgDiffTime.get() > HeavyRenderThresholdNanos) Effect[F].blocking(diff) else Effect[F].delay(diff)
      _      <- sendRaw(result)
      _      <- Effect[F].delay(remoteDomChangesPerformer.buffer.clear())
    } yield ()
  }

  def resolveFile(descriptor: String, file: Stream[F, Bytes]): F[Unit] =
    filesPromises
      .put(descriptor, file)
      .after(filesPromises.remove(descriptor))

  def resolveFileNames(descriptor: String, handler: List[(String, Long)]): F[Unit] =
    fileNamePromises
      .put(descriptor, handler)
      .after(fileNamePromises.remove(descriptor))

  def resolveFormData(descriptor: String, formData: Either[Throwable, FormData]): F[Unit] =
    formDataPromises
      .putEither(descriptor, formData)
      .after(formDataPromises.remove(descriptor))

  def registerCustomCallback(name: String)(f: String => F[Unit]): F[Unit] =
    Effect[F].delay {
      customCallbacks.put(name, f)
      ()
    }

  private def unescapeJsonString(s: String): String = {
    val sb  = new mutable.StringBuilder()
    var i   = 1
    val len = s.length - 1
    while (i < len) {
      val c             = s.charAt(i)
      var charsConsumed = 0
      if (c != '\\') {
        charsConsumed = 1
        sb.append(c)
      } else {
        charsConsumed = 2
        (s.charAt(i + 1): @switch) match {
          case '\\' => sb.append('\\')
          case '"'  => sb.append('"')
          case 'b'  => sb.append('\b')
          case 'f'  => sb.append('\f')
          case 'n'  => sb.append('\n')
          case 'r'  => sb.append('\r')
          case 't'  => sb.append('\t')
          case 'u' =>
            val code = s.substring(i + 2, i + 6)
            charsConsumed = 6
            sb.append(Integer.parseInt(code, 16).toChar)
        }
      }
      i += charsConsumed
    }
    sb.result()
  }

  private def parseMessage(json: String) = {
    val tokens = json
      .substring(1, json.length - 1) // remove brackets
      .split(",", 2)                 // split to tokens
    val callbackType = tokens(0)
    val args =
      if (tokens.length > 1) unescapeJsonString(tokens(1))
      else ""
    (callbackType.toInt, args)
  }

  rawClientMessages.foreach {
    case (CallbackType.Heartbeat.code, _) =>
      heartbeatLimit match {
        case Some(_) =>
          sendRaw("[16]")
        case None =>
          Effect[F].unit
      }
    case (CallbackType.ExtractPropertyResponse.code, args) =>
      val Array(descriptor, propertyType, value) = args.split(":", 3)
      propertyType.toInt match {
        case PropertyType.Error.code =>
          stringPromises
            .fail(descriptor, ClientSideException(value))
            .after(stringPromises.remove(descriptor))
        case _ =>
          stringPromises
            .put(descriptor, value)
            .after(stringPromises.remove(descriptor))
      }
    case (CallbackType.ExtractEventDataResponse.code, args) =>
      val Array(descriptor, value) = args.split(":", 2)
      stringPromises
        .put(descriptor, value)
        .after(stringPromises.remove(descriptor))
    case (CallbackType.EvalJsResponse.code, args) =>
      val Array(descriptor, status, json) = args.split(":", 3)
      status.toInt match {
        case EvalJsStatus.Success.code =>
          stringPromises
            .put(descriptor, json)
            .after(stringPromises.remove(descriptor))
        case EvalJsStatus.Failure.code =>
          stringPromises
            .fail(descriptor, ClientSideException(json))
            .after(stringPromises.remove(descriptor))
      }
    case (CallbackType.CustomCallback.code, args) =>
      val Array(name, arg) = args.split(":", 2)
      customCallbacks.get(name) match {
        case Some(f) => f(arg)
        case None    => Effect[F].unit
      }
    case (callbackType, args) =>
      Effect[F].fail(UnknownCallbackException(callbackType, args))
  }.runAsyncForget
}

object Frontend {

  final case class DomEventMessage(eventCounter: Int, target: Id, eventType: String)

  sealed abstract class Procedure(final val code: Int) {
    final val codeString = code.toString
  }

  object Procedure {
    case object SetEventCounter    extends Procedure(0)  // (id, eventType, n)
    case object Reload             extends Procedure(1)  // ()
    case object ListenEvent        extends Procedure(2)  // (type, preventDefault)
    case object ExtractProperty    extends Procedure(3)  // (id, propertyName, descriptor)
    case object ModifyDom          extends Procedure(4)  // (commands)
    case object Focus              extends Procedure(5)  // (id) {
    case object ChangePageUrl      extends Procedure(6)  // (path)
    case object UploadForm         extends Procedure(7)  // (id, descriptor)
    case object ReloadCss          extends Procedure(8)  // ()
    case object KeepAlive          extends Procedure(9)  // ()
    case object EvalJs             extends Procedure(10) // (code)
    case object ExtractEventData   extends Procedure(11) // (descriptor, id, eventType)
    case object ListFiles          extends Procedure(12) // (id, descriptor)
    case object UploadFile         extends Procedure(13) // (id, descriptor, fileName)
    case object RestForm           extends Procedure(14) // (id)
    case object DownloadFile       extends Procedure(15) // (descriptor, fileName)
    case object Heartbeat          extends Procedure(16) // ()
    case object ResetEventCounters extends Procedure(16) // ()

    val All = Set(
      SetEventCounter,
      Reload,
      ListenEvent,
      ExtractProperty,
      ModifyDom,
      Focus,
      ChangePageUrl,
      UploadForm,
      ReloadCss,
      KeepAlive,
      EvalJs,
      ExtractEventData,
      ListFiles,
      UploadFile,
      RestForm,
      DownloadFile,
      Heartbeat,
      ResetEventCounters
    )

    def apply(n: Int): Option[Procedure] =
      All.find(_.code == n)
  }

  sealed abstract class ModifyDomProcedure(final val code: Int) {
    final val codeString = code.toString
  }

  object ModifyDomProcedure {
    case object Create      extends ModifyDomProcedure(0) // (id, childId, xmlNs, tag)
    case object CreateText  extends ModifyDomProcedure(1) // (id, childId, text)
    case object Remove      extends ModifyDomProcedure(2) // (id, childId)
    case object SetAttr     extends ModifyDomProcedure(3) // (id, xmlNs, name, value, isProperty)
    case object RemoveAttr  extends ModifyDomProcedure(4) // (id, xmlNs, name, isProperty)
    case object SetStyle    extends ModifyDomProcedure(5) // (id, name, value)
    case object RemoveStyle extends ModifyDomProcedure(6) // (id, name)
  }

  sealed abstract class PropertyType(final val code: Int)

  object PropertyType {
    case object String  extends PropertyType(0)
    case object Number  extends PropertyType(1)
    case object Boolean extends PropertyType(2)
    case object Object  extends PropertyType(3)
    case object Error   extends PropertyType(4)
  }

  sealed abstract class EvalJsStatus(final val code: Int)

  object EvalJsStatus {
    case object Success extends EvalJsStatus(0)
    case object Failure extends EvalJsStatus(1)
  }

  sealed abstract class CallbackType(final val code: Int)

  object CallbackType {
    case object DomEvent                 extends CallbackType(0) // `$eventCounter:$elementId:$eventType`
    case object CustomCallback           extends CallbackType(1) // `$name:arg`
    case object ExtractPropertyResponse  extends CallbackType(2) // `$descriptor:$value`
    case object History                  extends CallbackType(3) // URL
    case object EvalJsResponse           extends CallbackType(4) // `$descriptor:$status:$value`
    case object ExtractEventDataResponse extends CallbackType(5) // `$descriptor:$dataJson`
    case object Heartbeat                extends CallbackType(6) // `$descriptor:$anyvalue`

    final val All = Set(
      DomEvent,
      CustomCallback,
      ExtractPropertyResponse,
      History,
      EvalJsResponse,
      ExtractEventDataResponse,
      Heartbeat
    )

    def apply(n: Int): Option[CallbackType] =
      All.find(_.code == n)
  }

  case class ClientSideException(message: String) extends Exception(message)
  case class UnknownCallbackException(callbackType: Int, args: String)
      extends Exception(s"Unknown callback $callbackType with args '$args' received")

  final case class DownloadFileMeta[F[_]: Effect](stream: Stream[F, Bytes], size: Option[Long], mimeType: String)

  final val ReloadMessage: String     = "[1]"
  final val HeavyRenderThresholdNanos = 50000000L
}
