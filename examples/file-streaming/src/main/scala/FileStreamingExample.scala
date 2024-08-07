import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import korolev.Context
import korolev.Context.FileHandler
import korolev.akka._
import korolev.data.Bytes
import korolev.effect.io.FileIO
import korolev.monix._
import korolev.server.{KorolevServiceConfig, StateLoader}
import korolev.state.javaSerialization._
import korolev.web.MimeTypes
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration.DurationInt

object FileStreamingExample extends SimpleAkkaHttpKorolevApp {

  case class State(aliveIndicator: Boolean, progress: Map[String, (Long, Long)], inProgress: Boolean)

  val globalContext = Context[Task, State, Any]

  import globalContext._
  import levsha.dsl._
  import levsha.dsl.html._

  val fileInput = elementId()

  def onUpload(access: Access): Task[Unit] =
    for {
      stream <- korolev.effect.Stream("hello", " ", "world").mat()
      bytes   = stream.map(s => Bytes.wrap(s.getBytes(StandardCharsets.UTF_8)))
      _      <- access.uploadFile("hello-world.txt", bytes, Some(11L), MimeTypes.`text/plain`)
    } yield ()

  def onDownload(access: Access): Task[Unit] = {

    def showProgress(fileName: String, loaded: Long, total: Long): Task[Unit] = access.transition { state =>
      val updated = state.progress + ((fileName, (loaded, total)))
      state.copy(progress = updated)
    }

    for {
      files <- access.downloadFilesAsStream(fileInput)
      _ <- access.transition(
             _.copy(
               progress = files.map { case (FileHandler(fileName, size), _) => (fileName, (0L, size)) }.toMap,
               inProgress = true
             )
           )
      _ <- Task.sequence {
             files.map { case (handler, data) =>
               val size = handler.size
               // File will be saved in 'downloads' directory
               // in the root of the example project
               val path = Paths.get(handler.fileName)
               data
                 .over(0L) { case (acc, chunk) =>
                   val loaded = chunk.fold(acc)(_.length + acc)
                   showProgress(handler.fileName, loaded, size)
                     .map(_ => loaded)
                 }
                 .to(FileIO.write(path))
             }
           }
      _ <- access.transition(_.copy(inProgress = false))
    } yield ()
  }

  val service = akkaHttpService {
    KorolevServiceConfig[Task, State, Any](
      stateLoader = StateLoader.default(State(aliveIndicator = false, Map.empty, inProgress = false)),
      document = { case State(aliveIndicator, progress, inProgress) =>
        optimize {
          Html(
            body(
              delay(1.second)(access => access.transition(_.copy(aliveIndicator = !aliveIndicator))),
              div(when(aliveIndicator)(backgroundColor @= "red"), "Online"),
              input(`type` := "file", multiple, fileInput),
              ul(
                progress.map { case (name, (loaded, total)) =>
                  li(s"$name: $loaded / $total")
                }
              ),
              button(
                "Upload to server",
                when(inProgress)(disabled),
                event("click")(onDownload)
              ),
              button(
                "Generate and download file from server",
                event("click")(onUpload)
              )
            )
          )
        }
      }
    )
  }
}
