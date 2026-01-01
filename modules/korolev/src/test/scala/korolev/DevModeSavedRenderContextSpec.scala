package korolev

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import korolev.effect.{Queue, Reporter, Scheduler}
import korolev.internal.{ApplicationInstance, DevMode, Frontend}
import korolev.state.StateStorage
import korolev.state.javaSerialization.*
import korolev.testExecution.*
import levsha.{Id, StatefulRenderContext, XmlNs}
import levsha.impl.DiffRenderContext
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * Regression coverage for Korolev's dev-mode startup path:
 *
 * If `korolev.dev=true` and a saved Levsha render-context exists for the session,
 * Korolev must:
 * - load that render-context
 * - diff the current render against it
 * - still start all internal streams so subsequent events/state updates work
 *
 * This path is not covered by the upstream `Issue14Spec` (which exercises the
 * "pre-rendered page" init path).
 */
class DevModeSavedRenderContextSpec extends AnyFlatSpec with Matchers with Eventually {

  import DevModeSavedRenderContextSpec.context._
  import Reporter.PrintReporter.Implicit

  "Korolev dev mode" should "initialize from a saved render context and still process DOM events after a state transition" in {
    val devModeKey = "korolev.dev"
    val prevDev    = sys.props.get(devModeKey)

    // Enable dev mode for this test only (see korolev.internal.DevMode).
    sys.props.update(devModeKey, "true")

    // Use a unique session id so we don't collide with other tests/dev runs.
    val qsid = Qsid("devmode", UUID.randomUUID().toString)

    // Write a valid saved Levsha render-context file for this session.
    // The content doesn't have to match the current render; it just needs to be a well-formed buffer
    // produced by `DiffRenderContext.save()`.
    val savedFile =
      try {
        val devMode = new DevMode.ForRenderContext(qsid.toString)
        val file    = devMode.file

        val rc  = DiffRenderContext[Nothing]()
        import levsha.dsl._
        import levsha.dsl.html._
        val doc = body(div("saved"))
        doc(rc)
        rc.finalizeDocument()

        devMode.saveRenderContext(rc)

        // Sanity-check: make sure we will actually exercise the devMode.saved path.
        devMode.saved shouldBe true

        file
      } catch {
        case t: Throwable =>
          // Restore property before failing the test.
          prevDev match {
            case Some(v) => sys.props.update(devModeKey, v)
            case None    => sys.props.remove(devModeKey)
          }
          throw t
      }

    try {
      val counter = new AtomicInteger(0)

      val incomingMessages = Queue[Future, String]()
      val frontend         = new Frontend[Future](incomingMessages.stream, None)
      val stateManager     = new StateStorage.SimpleInMemoryStateManager[Future]()

      val app = new ApplicationInstance[Future, DevModeSavedRenderContextSpec.S, Any](
        sessionId = qsid,
        frontend = frontend,
        rootPath = Root,
        router = Router.empty[Future, String],
        render = {
          DevModeSavedRenderContextSpec.render(
            firstEvents = Seq(
              event("click") { access =>
                access.transition { _ =>
                  counter.incrementAndGet()
                  "secondState"
                }
              },
              event("mousedown") { access =>
                counter.incrementAndGet()
                Future.unit
              }
            ),
            secondEvents = Seq(
              event("click") { access =>
                access.transition { _ =>
                  counter.incrementAndGet()
                  "firstState"
                }
              },
              event("mousedown") { access =>
                counter.incrementAndGet()
                Future.unit
              }
            )
          )
        },
        stateManager = stateManager,
        initialState = "firstState",
        reporter = Reporter.PrintReporter,
        scheduler = new Scheduler[Future](),
        createMiscProxy = (rc, k) =>
          new StatefulRenderContext[Context.Binding[Future, DevModeSavedRenderContextSpec.S, Any]] { proxy =>
            def currentContainerId: Id                                           = rc.currentContainerId
            def currentId: Id                                                    = rc.currentId
            def subsequentId: Id                                                 = rc.subsequentId
            def openNode(xmlns: XmlNs, name: String): Unit                       = rc.openNode(xmlns, name)
            def closeNode(name: String): Unit                                    = rc.closeNode(name)
            def setAttr(xmlNs: XmlNs, name: String, value: String): Unit         = rc.setAttr(xmlNs, name, value)
            def setStyle(name: String, value: String): Unit                      = rc.setStyle(name, value)
            def addTextNode(text: String): Unit                                  = rc.addTextNode(text)
            def addMisc(misc: Context.Binding[Future, DevModeSavedRenderContextSpec.S, Any]): Unit = k(rc, misc)
          },
        recovery = PartialFunction.empty,
        delayedRender = 0.seconds
      )

      def fireEvent(data: String) =
        incomingMessages.offerUnsafe(s"""[0,"$data"]""")

      Await.result(app.initialize(), 3.seconds)

      // Derive the click target id from the handler registry so the test is robust across
      // minor internal id allocation changes.
      val targetId =
        app.topLevelComponentInstance.allEventHandlers.keys
          .collectFirst {
            case eid if eid.`type` == "click" && eid.phase == levsha.events.EventPhase.Bubbling =>
              eid.target.mkString
          }
          .getOrElse(fail("Expected a click handler to be registered after initialize()"))

      // First click transitions to secondState.
      fireEvent(s"0:$targetId:click")

      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        Await.result(stateManager.read[DevModeSavedRenderContextSpec.S](Id.TopLevel), 200.millis) shouldBe Some("secondState")
        counter.get shouldBe 1
      }

      // Now validate outdated DOM behaviour.
      fireEvent(s"0:$targetId:click")     // outdated click -> ignored
      fireEvent(s"0:$targetId:mousedown") // mousedown -> processed
      fireEvent(s"1:$targetId:click")     // click with new counter -> processed

      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        counter.get shouldBe 3
      }
    } finally {
      // Best-effort cleanup to avoid leaving dev-mode artifacts around.
      try savedFile.delete()
      catch { case _: Throwable => () }

      prevDev match {
        case Some(v) => sys.props.update(devModeKey, v)
        case None    => sys.props.remove(devModeKey)
      }
    }
  }
}

object DevModeSavedRenderContextSpec {

  type S = String

  val context = Context[Future, DevModeSavedRenderContextSpec.S, Any]

  import context._
  import levsha.dsl._
  import levsha.dsl.html._

  def render(firstEvents: Seq[Event], secondEvents: Seq[Event]): Render = {
    case "firstState" =>
      body(
        div("Hello"),
        div(
          button("Click me", firstEvents)
        )
      )
    case "secondState" =>
      body(
        div("Hello"),
        ul(
          li("One", secondEvents),
          li("Two"),
          li("Three")
        ),
        div("Cow")
      )
  }
}


