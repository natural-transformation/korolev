import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import korolev._
import korolev.pekko._
import korolev.server._
import korolev.state.javaSerialization._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object PekkoHttpExample extends App {

  private implicit val actorSystem: ActorSystem = ActorSystem()

  val applicationContext = Context[Future, Boolean, Any]

  import applicationContext._
  import levsha.dsl._
  import html.{body, button, Html}

  private val config = KorolevServiceConfig[Future, Boolean, Any](
    stateLoader = StateLoader.default(false),
    document = s => optimize {
      Html(
        body(
          s"Hello akka-http: $s",
          button("Click me!",
            event("click")(_.transition(!_))
          )
        )
      )
    }
  )

  private val route = pekkoHttpService(config).apply(PekkoHttpServerConfig())

  Http().newServerAt("0.0.0.0", 8080).bindFlow(route)
}
