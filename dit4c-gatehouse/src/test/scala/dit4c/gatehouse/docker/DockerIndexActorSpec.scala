package dit4c.gatehouse.docker

import scala.concurrent.Future
import scala.concurrent.Promise

import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import akka.util.Timeout.intToTimeout
import spray.http.Uri
import spray.util.pimpFuture

class DockerIndexActorSpec extends Specification with NoTimeConversions {
  import scala.concurrent.duration._

  val system = ActorSystem("testSystem")
  implicit val timeout = Timeout(100.millis)

  import spray.util.pimpFuture

  def client = new DockerClient(java.net.URI.create("http://localhost:2375/")) {
    override def containerPorts: Future[Map[String, String]] =
      Promise.successful(Map("foo" -> "1.2.3.4:8080", "bar" -> "2.3.4.5:8888")).future
  }

  "DockerIndexActor" should {

    "respond to queries" in {
      import DockerIndexActor._
      val index: ActorRef = system.actorOf(Props(classOf[DockerIndexActor], client))

      (index ask PortQuery("foo"))
        .mapTo[PortReply] must equalTo(PortReply(Some("1.2.3.4:8080")))
        .await(retries = 2, timeout = 200.millis)

      (index ask PortQuery("bar"))
        .mapTo[PortReply] must equalTo(PortReply(Some("2.3.4.5:8888")))
        .await(retries = 2, timeout = 200.millis)

      (index ask PortQuery("doesnotexist"))
        .mapTo[PortReply] must equalTo(PortReply(None))
        .await(retries = 2, timeout = 200.millis)

      0 must_== 0
    }
  }
}


