package models

import providers.db.CouchDB
import play.api.libs.ws.WS
import providers.auth._
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import scala.concurrent.Future
import play.api.templates.JavaScript

class UserDAO(protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._

  def createWith(identity: Identity): Future[User] =
    db.newID.flatMap { id =>
      val user =
        User(id, None,
            identity.name, identity.emailAddress, Seq(identity.uniqueId))
      val data = Json.toJson(user)
      val holder = WS.url(s"${db.baseURL}/$id")
      holder.put(data).map { response =>
        response.status match {
          case 201 => user
        }
      }
    }

  def findWith(identity: Identity): Future[Option[User]] = {
    val tempView =
      TemporaryView(views.js.models.User_findWith_map(identity.uniqueId))
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").headOption.flatMap(fromJson[User])
      }
  }

  def get(id: String): Future[Option[User]] =
    WS.url(s"${db.baseURL}/$id").get.map { response =>
      (response.status match {
        case 200 => Some(response.json)
        case _ => None
      }).flatMap(fromJson[User])
    }


  implicit val userProjectsFormat: Format[User.Projects] = (
      (__ \ "owned").format[Set[String]] and
      (__ \ "shared").format[Set[String]]
    )(User.Projects.apply, unlift(User.Projects.unapply))

  implicit val userReads: Reads[User] = (
    (__ \ "_id").read[String] and
    (__ \ "_rev").readNullable[String] and
    (__ \ "name").readNullable[String] and
    (__ \ "email").readNullable[String] and
    (__ \ "identities").read[Seq[String]] and
    (__ \ "projects").read[User.Projects]
  )(User.apply _)

  implicit val userWrites: Writes[User] = (
    (__ \ "_id").write[String] and
    (__ \ "_rev").writeNullable[String] and
    (__ \ "name").writeNullable[String] and
    (__ \ "email").writeNullable[String] and
    (__ \ "identities").write[Seq[String]] and
    (__ \ "projects").write[User.Projects]
  )(unlift(User.unapply)).withTypeAttribute("User")

  implicit class ReadCombiner[A](r1: Reads[A]) {
    def or(r2: Reads[A]) = new Reads[A] {
      override def reads(json: JsValue) =
        r1.reads(json) match {
          case result: JsSuccess[A] => result
          case _: JsError => r2.reads(json)
        }
    }
  }

}


case class User(
    val id: String,
    val _rev: Option[String],
    val name: Option[String],
    val email: Option[String],
    val identities: Seq[String],
    val projects: User.Projects = User.Projects());

object User {
  case class Projects(
      owned: Set[String] = Set(),
      shared: Set[String] = Set())
}