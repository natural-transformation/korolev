package korolev.server

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import korolev.Context
import korolev.data.BytesLike
import korolev.effect.Effect
import korolev.effect.io.ServerSocket.ServerSocketHandler
import korolev.effect.syntax._
import korolev.state.{StateDeserializer, StateSerializer}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

abstract class KorolevApp[F[_]: Effect, B: BytesLike, S: StateSerializer: StateDeserializer, M](
  address: SocketAddress = new InetSocketAddress("localhost", 8080),
  gracefulShutdown: Boolean = false
) {

  implicit lazy val executionContext: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  val context: Context[F, S, M] = Context[F, S, M]

  val config: F[KorolevServiceConfig[F, S, M]]

  val channelGroup: AsynchronousChannelGroup =
    AsynchronousChannelGroup.withThreadPool(executionContext)

  private def logServerStarted(config: KorolevServiceConfig[F, S, M]) = Effect[F].delay {
    config.reporter.info(s"Server stated at $address")
  }

  private def addShutdownHook(config: KorolevServiceConfig[F, S, M], handler: ServerSocketHandler[F]) =
    Effect[F].delay {
      import config.reporter.Implicit
      Runtime.getRuntime.addShutdownHook(
        new Thread {
          override def run(): Unit = {
            config.reporter.info("Shutdown signal received.")
            config.reporter.info("Stopping serving new requests.")
            config.reporter.info("Waiting clients disconnection.")
            handler
              .stopServingRequests()
              .after(handler.awaitShutdown())
              .runSyncForget
          }
        }
      )
    }

  def main(args: Array[String]): Unit = {
    val job =
      for {
        cfg     <- config
        handler <- standalone.buildServer[F, B](korolevService(cfg), address, channelGroup, gracefulShutdown)
        _       <- logServerStarted(cfg)
        _       <- addShutdownHook(cfg, handler)
        _       <- handler.awaitShutdown()
      } yield ()
    Effect[F].run(job) match {
      case Left(e)  => e.printStackTrace()
      case Right(_) =>
    }
  }
}
