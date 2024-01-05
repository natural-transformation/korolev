/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package korolev.server

import korolev.{Context, Extension, Router}
import korolev.data.Bytes
import korolev.effect.{Effect, Reporter}
import korolev.state.IdGenerator
import korolev.web.{Path, PathAndQuery}
import korolev.web.{Path, PathAndQuery}
import levsha.Document
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class KorolevServiceConfig[F[_], S, M](
  stateLoader: StateLoader[F, S],
  stateStorage: korolev.state.StateStorage[F, S] = null, // By default it StateStorage.DefaultStateStorage
  http: PartialFunction[HttpRequest[F], F[HttpResponse[F]]] = PartialFunction.empty[HttpRequest[F], F[HttpResponse[F]]],
  router: Router[F, S] = Router.empty[F, S],
  rootPath: Path = PathAndQuery.Root,
  @deprecated(
    "Use `document` instead of `render`. Do not use `render` and `document` together.",
    "0.16.0"
  ) render: S => Document.Node[Context.Binding[F, S, M]] = (_: S) => levsha.dsl.html.body(),
  @deprecated("Add head() tag to `document`. Do not use `head` and `document` together.", "0.16.0") head: S => Seq[
    Document.Node[Context.Binding[F, S, M]]
  ] = (_: S) => Seq.empty,
  document: S => Document.Node[Context.Binding[F, S, M]] =
    (_: S) => levsha.dsl.html.Html(), // TODO (_: S) => levsha.dsl.html.Html(),
  connectionLostWidget: Document.Node[Context.Binding[F, S, M]] =
    KorolevServiceConfig.defaultConnectionLostWidget[Context.Binding[F, S, M]],
  extensions: List[Extension[F, S, M]] = Nil,
  idGenerator: IdGenerator[F] = IdGenerator.default[F](),
  heartbeatInterval: FiniteDuration = 5.seconds,
  reporter: Reporter = Reporter.PrintReporter,
  recovery: PartialFunction[Throwable, S => S] = PartialFunction.empty[Throwable, S => S],
  sessionIdleTimeout: FiniteDuration = 60.seconds,
  delayedRender: FiniteDuration = 0.seconds,
  heartbeatLimit: Option[Int] = Some(2),
  presetIds: Boolean = false,
  compressionSupport: Option[DeflateCompressionService[F]] =
    None // users should use java.util.zip.{Deflater, Inflater} in their service to make sure of the right compression format
)(implicit val executionContext: ExecutionContext)

object KorolevServiceConfig {

  def defaultConnectionLostWidget[MiscType]: Document.Node[MiscType] = {
    import levsha.dsl._
    import levsha.dsl.html._
    optimize {
      div(
        position @= "fixed",
        top @= "0",
        left @= "0",
        right @= "0",
        backgroundColor @= "lightyellow",
        borderBottom @= "1px solid black",
        padding @= "10px",
        "Connection lost. Waiting to resume."
      )
    }
  }
}

case class DeflateCompressionService[F[_]: Effect](
  decoder: Bytes => F[String],
  encoder: String => F[Bytes]
)
