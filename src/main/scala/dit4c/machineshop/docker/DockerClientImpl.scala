package dit4c.machineshop.docker

import scala.concurrent.{Future, future}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import spray.http._
import spray.http.ContentTypes._
import spray.json._
import dit4c.machineshop.docker.models._

class DockerClientImpl(val baseUrl: spray.http.Uri) extends DockerClient {

  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log = Logging(system, getClass)

  val SERVICE_PORT = 80

  // Overridden in unit tests
  def sendAndReceive: HttpRequest => Future[HttpResponse] =
    spray.client.pipelining.sendReceive

  override val containers = ContainersImpl

  class ContainerImpl(val id: String, val name: String, val status: ContainerStatus) extends DockerContainer {

    override def refresh = {

      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => DockerContainer = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        val obj = JsonParser(res.entity.asString).convertTo[JsObject]

        val status =
          if (obj.fields("State").asJsObject.fields("Running").convertTo[Boolean])
            ContainerStatus.Running
          else
            ContainerStatus.Stopped
        new ContainerImpl(id, name, status)
      }

      val pipeline: HttpRequest => Future[DockerContainer] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

      pipeline {
        import spray.httpx.RequestBuilding._
        Get(baseUrl + s"containers/$id/json")
      }

    }

    override def start = {
      import spray.httpx.ResponseTransformation._

      def parseResponse: HttpResponse => Unit = { res =>
        if (res.status == StatusCodes.NotFound) {
          throw new Exception("Container does not exist")
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      val createRequest =
        JsObject(
          "PortBindings" -> JsObject(),
          "PublishAllPorts" -> JsBoolean(true)
        )

      pipeline({
        import spray.httpx.RequestBuilding._
        Post(baseUrl + s"containers/$id/start")
          .withEntity(HttpEntity(`application/json`, createRequest.compactPrint))
      }).flatMap({
        case _: Unit => this.refresh
      })
    }

    override def stop(timeout: Duration) = {
      import spray.httpx.ResponseTransformation._

      def parseResponse: HttpResponse => Unit = { res =>
        if (res.status == StatusCodes.NotFound) {
          throw new Exception("Container does not exist")
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      // Cannot have negative timeout
      val t = Math.max(0, timeout.toSeconds)

      pipeline({
        import spray.httpx.RequestBuilding._
        Post(baseUrl + s"containers/$id/stop?t=$t")
      }).flatMap({
        case _: Unit => this.refresh
      })
    }

    override def delete = {
      import spray.httpx.ResponseTransformation._

      def parseResponse: HttpResponse => Unit = { res =>
        res.status match {
          case StatusCodes.NotFound =>
            throw new Exception("Container does not exist")
          case StatusCodes.InternalServerError =>
            throw new Exception(
                "Deletion failed due to server error:\n\n"+res.entity.asString)
          case _: StatusCode => Unit
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      pipeline({
        import spray.httpx.RequestBuilding._
        Delete(baseUrl + s"containers/$id?v=1")
      })
    }

  }

  object ContainersImpl extends DockerContainers {

    override def create(name: String) = {
      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => DockerContainer = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        val obj = JsonParser(res.entity.asString).convertTo[JsObject]

        new ContainerImpl(obj.getFields("Id").head.convertTo[String], name,
            ContainerStatus.Stopped)
      }

      val pipeline: HttpRequest => Future[DockerContainer] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

      val createRequest =
        JsObject(
          "Tty" -> JsBoolean(true),
          "AttachStdout" -> JsBoolean(true),
          "AttachStderr" -> JsBoolean(true),
          "Dns" -> JsNull,
          "Image" -> JsString("dit4c/python"),
          "ExposedPorts" -> JsObject("80/tcp" -> JsObject())
        )

      pipeline {
        if (!name.isValidProjectName) {
          throw new IllegalArgumentException(
              "Name must be a valid lower-case DNS label")
        }
        import spray.httpx.RequestBuilding._
        Post(baseUrl + s"containers/create?name=$name")
          .withEntity(HttpEntity(createRequest.compactPrint))
      }
    }

    override def list = {

      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => Seq[DockerContainer] = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        JsonParser(res.entity.asString)
          .convertTo[Seq[JsObject]]
          .map { obj: JsObject =>
            val Seq(jsId, namesWithSlashes, jsStatus) =
              obj.getFields("Id", "Names", "Status")
            // Get a single name without a slash
            val name: String = namesWithSlashes.convertTo[List[String]] match {
              case Seq(nameWithSlash: String) if nameWithSlash.startsWith("/") =>
                nameWithSlash.stripPrefix("/")
            }
            val status =
              if (jsStatus.convertTo[String].matches("Up .*"))
                ContainerStatus.Running
              else
                ContainerStatus.Stopped
            new ContainerImpl(jsId.convertTo[String], name, status)
          }
          .filter(_.name.isValidProjectName)
      }

      val pipeline: HttpRequest => Future[Seq[DockerContainer]] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

      pipeline {
        import spray.httpx.RequestBuilding._
        Get(baseUrl + "containers/json?all=1")
      }

    }

  }

  implicit class ProjectNameTester(str: String) {
    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidProjectName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }
  }

}