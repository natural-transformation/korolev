package korolev.zio.http

import _root_.zio.{Chunk, NonEmptyChunk, RIO, ZIO}
import _root_.zio.http.*
import _root_.zio.http.codec.PathCodec
import _root_.zio.stream.ZStream
import korolev.data.{Bytes, BytesLike}
import korolev.effect.{Queue, Reporter, Stream as KStream}
import korolev.server.{
  HttpRequest as KorolevHttpRequest,
  KorolevService,
  KorolevServiceConfig,
  WebSocketRequest,
  WebSocketResponse
}
import korolev.state.{StateDeserializer, StateSerializer}
import korolev.web.{PathAndQuery as PQ, Request as KorolevRequest, Response as KorolevResponse}
import korolev.zio.ChunkBytesLike
import korolev.zio.Zio2Effect
import korolev.zio.streams.*

class ZioHttpKorolev[R] {

  type ZEffect = Zio2Effect[R, Throwable]

  def service[S: StateSerializer: StateDeserializer, M](
    config: KorolevServiceConfig[RIO[R, *], S, M]
  )(implicit eff: ZEffect): Routes[R, Response] = {

    val korolevServer = korolev.server.korolevService(config)

    val rootPath = Path.decode(config.rootPath.mkString)

    val handler = Handler.fromFunctionZIO[(Path, Request)] { case (path, req) =>
      val subPath = path.encode
      val response =
        if (matchWebSocket(req)) routeWsRequest(req, subPath, korolevServer, config.reporter)
        else routeHttpRequest(subPath, req, korolevServer)
      response.mapError(Response.fromThrowable)
    }

    Routes.singleton(handler).nest(prefixCodec(rootPath))
  }

  private def matchWebSocket(req: Request): Boolean =
    req.method == Method.GET && containsUpgradeHeader(req)

  private def routeHttpRequest(subPath: String, req: Request, korolevServer: KorolevService[RIO[R, *]])(implicit
    eff: ZEffect
  ): ZIO[R, Throwable, Response] = {
    req match {
      case req if req.method == Method.GET =>
        val body           = KStream.empty[RIO[R, *], Bytes]
        val korolevRequest = mkKorolevRequest(req, subPath, body)
        handleHttpResponse(korolevServer, korolevRequest)

      case req =>
        for {
          stream        <- toKorolevBody(req.body)
          korolevRequest = mkKorolevRequest(req, subPath, stream)
          response      <- handleHttpResponse(korolevServer, korolevRequest)
        } yield {
          response
        }
    }
  }

  // Build a literal prefix codec so this Routes only matches under rootPath.
  private def prefixCodec(prefix: Path): PathCodec[Unit] =
    prefix.segments.foldLeft(PathCodec.empty: PathCodec[Unit]) { (codec, segment) =>
      codec / PathCodec.literal(segment)
    }

  private def containsUpgradeHeader(req: Request): Boolean = {
    val found = for {
      _ <- req.rawHeader(Header.Connection).filter(_.toLowerCase.indexOf("upgrade") > -1)
      _ <- req.rawHeader(Header.Upgrade).filter(_.toLowerCase.indexOf("websocket") > -1)
    } yield {}
    found.isDefined
  }

  private[http] def parseProtocols(req: Request): Seq[String] =
    parseProtocolsValues(req.headers.getAll(Header.SecWebSocketProtocol).map(_.renderedValue))

  private[http] def parseProtocolsValues(values: Seq[String]): Seq[String] =
    values
      .flatMap(_.split(',').iterator.map(_.trim))
      .filter(_.nonEmpty)

  private val SupportedProtocols = Set("json", "json-deflate")

  private[http] def acceptsProtocols(protocols: Seq[String]): Boolean =
    protocols.exists(SupportedProtocols.contains)

  private def routeWsRequest[S: StateSerializer: StateDeserializer, M](
    req: Request,
    fullPath: String,
    korolevServer: KorolevService[RIO[R, *]],
    reporter: Reporter
  )(implicit eff: ZEffect): ZIO[R, Throwable, Response] = {

    val fromClientKQueue = Queue[RIO[R, *], Bytes]()
    val korolevRequest =
      mkKorolevRequest[KStream[RIO[R, *], Bytes]](req, fullPath, fromClientKQueue.stream)
    val protocols = parseProtocols(req)
    if (!acceptsProtocols(protocols)) {
      ZIO.succeed(Response(status = Status.BadRequest))
    } else {
      for {
        // FIXME https://github.com/zio/zio-http/issues/2278
        response <- korolevServer.ws(WebSocketRequest(korolevRequest, protocols))
        (selectedProtocol, toClient) = response match {
                                         case WebSocketResponse(KorolevResponse(_, outStream, _, _), selectedProtocol) =>
                                           selectedProtocol -> outStream
                                             .map(out => WebSocketFrame.Binary(out.as[Chunk[Byte]]))
                                             .toZStream
                                         case null =>
                                           throw new RuntimeException
                                       }
        route <- buildSocket(toClient, fromClientKQueue, reporter)
      } yield {
        route.addHeader(Header.SecWebSocketProtocol(NonEmptyChunk(selectedProtocol)))
      }
    }
  }

  private def buildSocket(
    toClientStream: ZStream[R, Throwable, WebSocketFrame],
    fromClientKQueue: Queue[RIO[R, *], Bytes],
    reporter: Reporter
  ): RIO[R, Response] = {
    val socket = Handler.webSocket { channel =>
      runSocket(channel.send, channel.receiveAll, toClientStream, fromClientKQueue, reporter)
    }

    Response.fromSocketApp(socket)
  }

  private[http] def runSocket(
    send: ChannelEvent[WebSocketFrame] => RIO[R, Unit],
    receiveAll: PartialFunction[ChannelEvent[WebSocketFrame], RIO[R, Unit]] => RIO[R, Unit],
    toClientStream: ZStream[R, Throwable, WebSocketFrame],
    fromClientKQueue: Queue[RIO[R, *], Bytes],
    reporter: Reporter
  ): RIO[R, Unit] =
    for {
      // Start sending immediately. zio-http 3.x may not emit HandshakeComplete reliably.
      sendFiber <- toClientStream
                     .mapZIO(frame => send(ChannelEvent.Read(frame)))
                     .runDrain
                     .catchAllCause { cause =>
                       cause.failureOption.orElse(cause.dieOption) match {
                         case Some(err) =>
                           ZIO.succeed(reporter.error("WebSocket send failed", err))
                         case None =>
                           ZIO.unit
                       }
                     }
                     .forkDaemon
      _ <- receiveAll {
             case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              // No-op: we start sending immediately because this event is not reliable in zio-http 3.x.
               ZIO.unit
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
              // Close the queue so sessions can clean up and fall back to long-polling.
              fromClientKQueue.close()
             case ChannelEvent.Read(WebSocketFrame.Binary(bytes)) =>
               fromClientKQueue.offer(Bytes.wrap(bytes)).unit
             case ChannelEvent.Read(WebSocketFrame.Text(t)) =>
               fromClientKQueue.offer(BytesLike[Bytes].utf8(t)).unit
             case ChannelEvent.Read(WebSocketFrame.Close(_, _)) =>
               fromClientKQueue.close()
            case ChannelEvent.ExceptionCaught(cause) =>
              fromClientKQueue.close() *> ZIO.fail(cause)
             case ChannelEvent.Unregistered =>
              // Unregistered can happen without a close frame; close the queue to unblock cleanup.
              fromClientKQueue.close()
             case frame =>
               ZIO.fail(new Exception(s"Invalid frame type ${frame.getClass.getName}"))
           }.ensuring(sendFiber.interrupt)
    } yield ()

  private def mkKorolevRequest[B](request: Request, path: String, body: B): KorolevRequest[B] = {
    val cookies = request.rawHeader(Header.Cookie)
    val params  = request.url.queryParams.map.collect { case (k, v) if v.nonEmpty => (k, v.head) }
    KorolevRequest(
      pq = PQ.fromString(path).withParams(params),
      method = KorolevRequest.Method.fromString(request.method.name),
      renderedCookie = cookies.orNull,
      contentLength = request.header(Header.ContentLength).map(_.length),
      headers = {
        val contentType = request.header(Header.ContentType)
        val contentTypeHeader = {
          contentType.map { ct =>
            if (ct.renderedValue.contains("multipart")) Headers(ct) else Headers.empty
          }.getOrElse(Headers.empty)
        }
        (request.headers.toList ++ contentTypeHeader).map(header => header.headerName -> header.renderedValue)
      },
      body = body
    )
  }

  private def handleHttpResponse(
    korolevServer: KorolevService[RIO[R, *]],
    korolevRequest: KorolevHttpRequest[RIO[R, *]]
  ): ZIO[R, Throwable, Response] =
    korolevServer.http(korolevRequest).flatMap { case KorolevResponse(status, stream, responseHeaders, contentLength) =>
      val headers = Headers(responseHeaders.map { case (name, value) => Header.Custom(name, value) })
      val body: ZStream[R, Throwable, Byte] =
        stream.toZStream.flatMap { (bytes: Bytes) =>
          ZStream.fromIterable(bytes.as[Array[Byte]])
        }

      val bodyZio =
        contentLength match {
          case Some(length) => Body.fromStreamEnv(body, length)
          case None         => Body.fromStreamChunkedEnv(body)
        }

      bodyZio.map { body =>
        Response(
          status = HttpStatusConverter.fromKorolevStatus(status),
          headers = headers,
          body = body
        )
      }
    }

  private def toKorolevBody(body: Body)(implicit eff: ZEffect): RIO[R, KStream[RIO[R, *], Bytes]] =
    ZStreamOps[R, Byte](body.asStream).toKorolev(eff).map { kStream =>
      kStream.map(bytes => Bytes.wrap(bytes.toArray))
    }

}
