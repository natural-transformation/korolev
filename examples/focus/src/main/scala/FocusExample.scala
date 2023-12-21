import korolev._
import korolev.akka._
import korolev.server._
import korolev.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object FocusExample extends SimpleAkkaHttpKorolevApp {

  val globalContext = Context[Future, Boolean, Any]

  import globalContext._
  import html._
  import levsha.dsl._

  // Handler to input
  val inputId = elementId()

  val service: AkkaHttpService = akkaHttpService {
    KorolevServiceConfig[Future, Boolean, Any](
      stateLoader = StateLoader.default(false),
      document = _ =>
        optimize {
          Html(
            body(
              div("Focus example"),
              div(
                input(
                  inputId,
                  `type`      := "text",
                  placeholder := "Wanna get some focus?"
                )
              ),
              div(
                button(
                  event("click") { access =>
                    access.focus(inputId)
                  },
                  "Click to focus"
                )
              )
            )
          )
        }
    )
  }
}
