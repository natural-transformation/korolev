package korolev.server.internal.services

import korolev.Qsid
import korolev.effect.Effect
import korolev.server.{KorolevServiceConfig, StateLoader}
import korolev.server.internal.Html5RenderContext
import korolev.testExecution.defaultExecutor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ExecutionContext, Future}

class PageServiceSpec extends AnyFlatSpec with Matchers {

  private def mkConfig(protocolsEnabled: Boolean)(implicit ec: ExecutionContext) =
    KorolevServiceConfig[Future, Unit, Unit](
      stateLoader = StateLoader.default(()),
      webSocketProtocolsEnabled = protocolsEnabled
    )

  "appendScripts" should "include wsp flag when protocols are disabled" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    implicit val ec: ExecutionContext = defaultExecutor
    val service = new PageService[Future, Unit, Unit](mkConfig(protocolsEnabled = false))
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = false)

    service.appendScripts(rc, Qsid("device", "session"))

    rc.mkString should include("wsp:false")
  }

  it should "omit wsp flag when protocols are enabled" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    implicit val ec: ExecutionContext = defaultExecutor
    val service = new PageService[Future, Unit, Unit](mkConfig(protocolsEnabled = true))
    val rc = new Html5RenderContext[Future, Unit, Unit](presetId = false)

    service.appendScripts(rc, Qsid("device", "session"))

    rc.mkString should not include "wsp:false"
  }
}
