package korolev.server.internal.services

import korolev.Qsid
import korolev.effect.{Effect, Stream}
import korolev.server.{KorolevServiceConfig, StateLoader}
import korolev.state.javaSerialization._
import korolev.testExecution.defaultExecutor
import korolev.web.{PathAndQuery, Request}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SessionsServiceSpec extends AnyFlatSpec with Matchers {

  "createAppIfNeeded" should "rebuild missing state and create app" in {
    implicit val effect: Effect[Future] = Effect.futureEffect

    val config = KorolevServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial-state")
    )
    val pageService    = new PageService[Future, String, Any](config)
    val sessionsService = new SessionsService[Future, String, Any](config, pageService)

    val qsid    = Qsid("device", "session")
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incomingStream = Stream.endless[Future, String]

    val resultF =
      sessionsService
        .createAppIfNeeded(qsid, request, incomingStream)
        .flatMap(_ => sessionsService.getApp(qsid))

    val result = Await.result(resultF, 3.seconds)
    result.isDefined shouldBe true
  }
}
