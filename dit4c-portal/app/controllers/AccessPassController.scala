package controllers

import java.util.Base64

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import com.mohiva.play.silhouette.api.Silhouette
import com.softwaremill.tagging.@@

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.ByteString
import akka.util.Timeout
import domain.UserAggregate
import play.api.mvc.Controller
import services.UserSharder
import utils.auth.DefaultEnv

class AccessPassController(
    val userSharder: ActorRef @@ UserSharder.type,
    val silhouette: Silhouette[DefaultEnv])(implicit system: ActorSystem, materializer: Materializer)
    extends Controller {
  import play.api.libs.concurrent.Execution.Implicits._

  def redeemAccessPass(schedulerId: String, encodedAccessPass: String) =
    silhouette.SecuredAction.async { implicit request =>
      implicit val timeout = Timeout(1.minute)
      for {
        signedData <- Future { ByteString(Base64.getUrlDecoder.decode(encodedAccessPass)) }
        queryMsg = UserSharder.Envelope(request.identity.id,
            UserAggregate.AddAccessPass(schedulerId, signedData))
        queryResponse <- (userSharder ? queryMsg)
      } yield queryResponse match {
        case UserAggregate.AccessPassAdded =>
          Redirect(routes.MainController.index())
        case UserAggregate.AccessPassRejected(reason) =>
          InternalServerError(reason)
      }
    }
}