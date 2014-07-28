package controllers

import java.util.UUID
import scala.concurrent.ExecutionContext
import org.junit.runner.RunWith
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.PlaySpecification
import providers.db.CouchDB
import providers.db.EphemeralCouchDBInstance
import org.specs2.runner.JUnitRunner
import models._
import providers.auth.Identity
import play.api.test.WithApplication
import play.api.Play
import providers.InjectorPlugin
import utils.SpecUtils

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class UserControllerSpec extends PlaySpecification with SpecUtils {

  "UserController" should {

    "provide JSON for users" in new WithApplication(fakeApp) {
      val controller = injector.getInstance(classOf[UserController])

      val withoutLogin = controller.currentUser(FakeRequest())
      status(withoutLogin) must_== 404

      val db = injector.getInstance(classOf[CouchDB.Database])
      val session = new UserSession(db, new Identity() {
        def uniqueId = "test:testuser"
        def name = Some("Test User")
        def emailAddress = Some("user@example.test")
      })
      val response = controller.get(session.user.id)(session.newRequest)
      status(response) must_== 200
      contentAsJson(response) must_== Json.obj(
        "id" -> session.user.id,
        "name" -> session.user.name.get,
        "email" -> session.user.email.get
      )
      val etag: String = header("ETag", response) match {
        case Some(s) => s
        case None => failure("ETag must be sent with user record"); ""
      }
      val ifMatchResponse = controller.get(session.user.id)(
          session.newRequest.withHeaders("If-None-Match" -> etag))
      status(ifMatchResponse) must_== 304
    }

    "provide redirect to current user" in new WithApplication(fakeApp) {
      val controller = injector.getInstance(classOf[UserController])

      val withoutLogin = controller.currentUser(FakeRequest())
      status(withoutLogin) must_== 404

      val user = {
        val dao = new UserDAO(injector.getInstance(classOf[CouchDB.Database]))
        await(dao.createWith(new Identity() {
          def uniqueId = "test:testuser"
          def name = Some("Test User")
          def emailAddress = Some("user@example.test")
        }))
      }
      val withLogin = controller.currentUser(
          FakeRequest().withSession("userId" -> user.id))
      status(withLogin) must_== 303
      redirectLocation(withLogin) must beSome(s"/users/${user.id}")
      header("Cache-Control", withLogin) must beSome("private, must-revalidate")
    }

  }

}