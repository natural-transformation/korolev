import korolev._
import korolev.akka._
import korolev.server._
import korolev.state.javaSerialization._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object EvalJsExample extends SimpleAkkaHttpKorolevApp {

  val globalContext = Context[Future, String, Any]

  import globalContext._
  import html._
  import levsha.dsl._

  private def onClick(access: Access) =
    for {
      result <- access.evalJs("window.confirm('Do you have cow superpower?')")
      _      <- access.transition(s => result.toString)
    } yield ()

  val service = akkaHttpService {
    KorolevServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default("nothing"),
      document = { s =>
        optimize {
          Html(
            head(
              script(
                """var x = 0;
                  |setInterval(() => {
                  |  x++;
                  |  Korolev.invokeCallback('myCallback', x.toString());
                  |}, 1000);
                  |""".stripMargin
              )
            ),
            body(
              button("Click me", event("click")(onClick)),
              div(s)
            )
          )
        }
      },
      extensions = List(
        Extension { access =>
          for (_ <- access.registerCallback("myCallback")(arg => Future(println(arg))))
            yield Extension.Handlers()
        }
      )
    )
  }
}
