package korolev.zio.http

import korolev.Context
import korolev.server.{KorolevServiceConfig, StateLoader}
import korolev.state.javaSerialization.*
import korolev.web.PathAndQuery
import korolev.zio.Zio2Effect
import levsha.dsl.*
import levsha.dsl.html.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.http.*
import zio.{Exit, RIO, Runtime, Task, Unsafe, ZIO}

import scala.concurrent.ExecutionContext

/**
 * Tests for ZioHttpKorolev integration to verify error handling behavior.
 * 
 * Issue observed: When using korolev-zio-http with zio-http 3.x, requests
 * to bridge/long-polling endpoints fail with InternalServerError.
 * 
 * The suspected issue is that mapError(Response.fromThrowable) at line 39
 * of ZioHttpKorolev.scala causes the handler error type to become Response,
 * which may not be properly handled by zio-http routing.
 */
final class ZioHttpKorolevSpec extends AnyFlatSpec with Matchers {

  private type AppTask[A] = RIO[Any, A]

  implicit private val runtime: Runtime[Any] = Runtime.default
  implicit private val ec: ExecutionContext = Runtime.defaultExecutor.asExecutionContext
  implicit private val effect: Zio2Effect[Any, Throwable] = new Zio2Effect[Any, Throwable](runtime, identity, identity)

  private val ctx = Context[ZIO[Any, Throwable, *], String, Any]
  import ctx.*

  private val simpleDocument: ctx.Render = { state =>
    optimize {
      Html(
        body(
          div(s"State: $state"),
          button(
            "Click me",
            event("click")(access => access.transition(_ => "clicked"))
          )
        )
      )
    }
  }

  private val simpleConfig = KorolevServiceConfig[AppTask, String, Any](
    stateLoader = StateLoader.default("initial"),
    rootPath = PathAndQuery.Root,
    document = simpleDocument
  )

  "ZioHttpKorolev.service" should "create routes that serve the root page" in {
    val korolev = new ZioHttpKorolev[Any]
    val routes = korolev.service(simpleConfig)

    val testApp = routes.toHandler
    val request = Request.get(URL(Path.root))

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(
        ZIO.scoped(testApp.runZIO(request))
      )
    }

    result match {
      case Exit.Success(response) =>
        response.status shouldBe Status.Ok
        // The response should contain HTML
        val bodyResult = Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.run(response.body.asString)
        }
        bodyResult match {
          case Exit.Success(body) =>
            body should include("State: initial")
          case Exit.Failure(cause) =>
            fail(s"Failed to get response body: $cause")
        }
      case Exit.Failure(cause) =>
        fail(s"Request failed: $cause")
    }
  }

  it should "handle POST requests to bridge/long-polling without error" in {
    val korolev = new ZioHttpKorolev[Any]
    val routes = korolev.service(simpleConfig)

    val testApp = routes.toHandler
    
    // First make a GET to get a session
    val getRequest = Request.get(URL(Path.root))
    val getResult = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.scoped(testApp.runZIO(getRequest)))
    }
    
    getResult match {
      case Exit.Success(response) =>
        // Extract session ID from the set-cookie or body
        response.status shouldBe Status.Ok
        
        // Try a POST to /bridge/long-polling/session-id
        // This tests whether the korolev service properly handles bridge requests
        val postRequest = Request.post(
          URL(Path.decode("/bridge/long-polling/test-session")),
          Body.empty
        )
        
        val postResult = Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.run(ZIO.scoped(testApp.runZIO(postRequest)))
        }
        
        postResult match {
          case Exit.Success(postResponse) =>
            // Should NOT be InternalServerError (500)
            postResponse.status should not be Status.InternalServerError
          case Exit.Failure(cause) =>
            // If it fails, it should NOT be because of mapError(Response.fromThrowable) 
            // creating an error Response that's then logged as unhandled
            fail(s"Bridge POST request failed unexpectedly: $cause")
        }
        
      case Exit.Failure(cause) =>
        fail(s"Initial GET request failed: $cause")
    }
  }

  it should "properly convert errors to HTTP 500 responses via mapError" in {
    // Test that errors are properly converted, not left as unhandled
    val korolev = new ZioHttpKorolev[Any]
    
    // Create a config with a document that throws an exception
    val failingDocument: ctx.Render = { _ =>
      throw new RuntimeException("Simulated render failure")
    }
    
    val failingConfig = KorolevServiceConfig[AppTask, String, Any](
      stateLoader = StateLoader.default("initial"),
      rootPath = PathAndQuery.Root,
      document = failingDocument
    )
    
    val routes = korolev.service(failingConfig)
    val testApp = routes.toHandler
    val request = Request.get(URL(Path.root))

    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.scoped(testApp.runZIO(request)))
    }

    // When there's an error, mapError(Response.fromThrowable) should convert it
    // to a Response error, which zio-http should then handle as a 500 response
    result match {
      case Exit.Success(response) =>
        // Should get a 500 response, not crash
        response.status shouldBe Status.InternalServerError
      case Exit.Failure(cause) =>
        // This is the bug: errors shouldn't escape as failures when
        // mapError(Response.fromThrowable) is used - they should be converted
        // to InternalServerError responses
        fail(s"Error escaped as failure instead of being converted to 500 response: $cause")
    }
  }
}
