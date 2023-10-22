package korolev.zio

import korolev.effect.{Effect as KorolevEffect, Stream as KorolevStream}
import zio.{Chunk, RIO, ZIO, ZManaged}
import zio.stream.ZStream

package object streams {

  implicit class KorolevSreamOps[R, O](stream: KorolevStream[RIO[R, *], O]) {

    def toZStream: ZStream[R, Throwable, O] =
      ZStream.unfoldM(()) { _ =>
        stream
          .pull()
          .map(mv => mv.map(v => (v, ())))
      }
  }

  implicit class ZStreamOps[R, O](stream: ZStream[R, Throwable, O]) {

    type F[A] = RIO[R, A]

    def toKorolev(implicit eff: KorolevEffect[F]): ZManaged[R, Throwable, KorolevStream[F, Seq[O]]] =
      stream.process.map { zPull =>
        new ZKorolevStream(zPull)
      }
  }

  private[streams] class ZKorolevStream[R, O](
    zPull: ZIO[R, Option[Throwable], Chunk[O]]
  )(implicit eff: KorolevEffect[RIO[R, *]])
      extends KorolevStream[RIO[R, *], Seq[O]] {

    def pull(): RIO[R, Option[Seq[O]]] =
      zPull.option

    def cancel(): RIO[R, Unit] =
      ZIO.dieMessage("Can't cancel ZStream from Korolev")
  }
}
