package korolev.zio

import korolev.effect.{Effect as KorolevEffect, Stream as KorolevStream}
import zio._
import zio.stream.ZStream

package object streams {

  implicit class KorolevStreamOps[R, O](stream: KorolevStream[RIO[R, *], O]) {

    def toZStream: ZStream[R, Throwable, O] =
      ZStream.unfoldZIO(()) { _ =>
        stream
          .pull()
          .map(mv => mv.map(v => (v, ())))
      }
  }

  private type Finalizer = Exit[Any, Any] => UIO[Unit]

  implicit class ZStreamOps[R, O](stream: ZStream[R, Throwable, O]) {

    def toKorolev(implicit eff: KorolevEffect[RIO[R, *]]): RIO[R, ZKorolevStream[R, O]] = {
      Scope.make.flatMap { scope =>
        scope.use[R](
          for {
            pull    <- stream.toPull
            zStream = ZKorolevStream[R, O](pull, scope.close(_))
          } yield zStream
        )
      }
    }
  }

  private[streams] case class ZKorolevStream[R, O](
    zPull: ZIO[R, Option[Throwable], Chunk[O]],
    finalizer: Finalizer
  )(implicit eff: KorolevEffect[RIO[R, *]])
      extends KorolevStream[RIO[R, *], Seq[O]] {

    def pull(): RIO[R, Option[Seq[O]]] =
      zPull.option

    def cancel(): RIO[R, Unit] =
      finalizer(Exit.unit)
  }
}
