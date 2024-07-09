package korolev.zio

import korolev.effect._
import korolev.effect.Reporter.PrintReporter.Implicit

import zio.{Queue as _, Hub as _, Console as _, _}
import zio.test.Assertion._
import zio.test.TestAspect.{identity as _, _}
import zio.test._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits.global

object Zio2StreamSpec extends ZIOSpecDefault {
  implicit val effect: Zio2Effect[Any, Throwable] = new Zio2Effect[Any, Throwable](runtime, identity, identity)

  def spec = suite("Stream spec")(
    suite("buffer")(
      test("has same elements eventually") {
        val N = 200
        val queue = Queue[Task, Int](32)
        val hub = Hub[Task, Int](queue.stream, 16)
        for {
          promise1 <- Promise.make[Nothing, Unit]
          promise2 <- Promise.make[Nothing, Unit]
          promise3 <- Promise.make[Nothing, Unit]
          promise4 <- Promise.make[Nothing, Unit]
          stream1F <- hub
            .newStream()
            .tap(_ => promise1.succeed(()))
            .map(_.foldAsync(List.empty[Int])((acc, i) => promise3.succeed(()).when(i == N).as(acc :+ i)))
            .flatten
            .fork
          stream2F <- hub
            .newStream()
            .tap(_ => promise2.succeed(()))
            .map(_.buffer(FiniteDuration(40, "milli"), 32))
            .map(_.foldAsync(List.empty[Int])((acc, i) => promise4.succeed(()).when(i contains N).as(acc ++ i)))
            .flatten
            .fork

          _ <- Random
            .nextIntBetween(35, 50)
            .flatMap(adjust => TestClock.adjust(adjust.millis))
            .repeatWhile(_ => true)
            .fork
          _ <- promise1.await
          _ <- promise2.await
          _ <- ZIO.foreach(1 to N)(queue.enqueue)
          _ <- promise3.await
          _ <- promise4.await
          _ <- queue.close()
          stream1 <- stream1F.join
          stream2 <- stream2F.join
        } yield assertTrue(stream1 == stream2)
      } @@ TestAspect.nonFlaky(10)
    )
  )
}
