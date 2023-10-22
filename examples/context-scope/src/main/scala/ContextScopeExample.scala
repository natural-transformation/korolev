import ViewState.Tab.{About, Blog}
import korolev.*
import korolev.akka.*
import korolev.server.*
import korolev.state.javaSerialization.*
import korolev.util.Lens
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ContextScopeExample extends SimpleAkkaHttpKorolevApp {

  val context = Context[Future, ViewState, Any]

  import context._
  import levsha.dsl._
  import levsha.dsl.html._

  final private val blogLens = Lens[ViewState, Blog](
    read = { case ViewState(_, s: Blog) => s },
    write = { case (orig, s) => orig.copy(tab = s) }
  )

  final private val blogView = new BlogView(context.scope(blogLens))

  val service: AkkaHttpService = akkaHttpService {
    KorolevServiceConfig[Future, ViewState, Any](
      stateLoader = StateLoader.default(ViewState("My blog", Blog.default)),
      document = { state =>
        val isBlog  = state.tab.isInstanceOf[Blog]
        val isAbout = state.tab.isInstanceOf[About]

        optimize {
          Html(
            body(
              h1(state.blogName),
              div(
                div(
                  when(isBlog)(fontWeight @= "bold"),
                  when(isBlog)(borderBottom @= "1px solid black"),
                  event("click")(access => access.transition(_.copy(tab = Blog.default))),
                  padding @= "5px",
                  display @= "inline-block",
                  "Blog"
                ),
                div(
                  when(isAbout)(fontWeight @= "bold"),
                  when(isAbout)(borderBottom @= "1px solid black"),
                  event("click")(access => access.transition(_.copy(tab = About.default))),
                  padding @= "5px",
                  display @= "inline-block",
                  "About"
                )
              ),
              div(
                marginTop @= "20px",
                state.tab match {
                  case blog: Blog   => blogView(blog)
                  case about: About => p(about.text)
                }
              )
            )
          )
        }
      }
    )
  }
}
