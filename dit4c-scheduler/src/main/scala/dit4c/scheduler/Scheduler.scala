package dit4c.scheduler

import dit4c.scheduler.utils.SchedulerConfig
import scala.concurrent.Future
import akka.actor.Props
import dit4c.scheduler.service.ClusterManager
import dit4c.scheduler.domain.ConfigProvider
import dit4c.scheduler.runner.RktRunner
import java.nio.file.Paths
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Terminated
import akka.actor.ActorLogging
import akka.stream.ActorMaterializer
import scala.concurrent.Await
import scala.concurrent.duration._
import dit4c.scheduler.service.PortalMessageBridge
import akka.pattern.BackoffSupervisor
import akka.pattern.Backoff
import akka.event.LoggingReceive
import dit4c.scheduler.domain.Instance
import dit4c.scheduler.service.KeyManager
import akka.util.Timeout
import scala.concurrent.ExecutionContext
import akka.actor.AllForOneStrategy

object Scheduler {

  def apply(config: SchedulerConfig): Unit = {
    (new SchedulerEnvironment(config))()
  }

  protected class SchedulerEnvironment(config: SchedulerConfig) extends utils.ActorModule {

    override def appName = config.name

    def apply(): Future[Unit] = {
      import system.dispatcher
      system.actorOf(Props(classOf[Scheduler], config), "scheduler")
      system.whenTerminated.map(_ => ())
    }

  }

}

class Scheduler(config: SchedulerConfig) extends Actor with ActorLogging {
  import akka.http.scaladsl.server._
  import akka.actor.SupervisorStrategy._

  override val supervisorStrategy = AllForOneStrategy() {
    case _: Exception => Escalate
  }

  override def preStart {
    import context.dispatcher
    implicit val materializer = ActorMaterializer()(context.system)
    val keyManager = context.actorOf(
        KeyManager.props(config.armoredPgpKeyrings),
        "key-manager")
    val clusterAggregateManager = context.actorOf(
        ClusterManager.props(configProvider(keyManager), config.knownClusters),
        "cluster-aggregate-manager")
    val pmbSupervisor = context.actorOf(BackoffSupervisor.props(
        Backoff.onStop(
          Props(classOf[PortalMessageBridge], keyManager, config.portalUri),
          childName = "portal-message-bridge",
          minBackoff = 500.milliseconds,
          maxBackoff = 15.seconds,
          randomFactor = 0.1)),
        "pmb-supervisor")
    // TODO: remove need for this kludge
    context.system.eventStream.subscribe(self, classOf[Instance.StatusReport])
  }

  override def postStop {
    context.system.terminate
  }

  override def receive = LoggingReceive {
    case msg: KeyManager.Command =>
      context.child("key-manager").foreach { child =>
        child.forward(msg)
      }
    case msg if Some(sender) == context.child("cluster-aggregate-manager") =>
      context.child("pmb-supervisor").foreach { child =>
        child.forward(msg)
      }
    case msg if Some(sender) == context.child("pmb-supervisor") =>
      context.child("cluster-aggregate-manager").foreach { child =>
        child.forward(msg)
      }
    case msg: Instance.StatusReport => // TODO: remove need for this kludge
      context.child("pmb-supervisor").foreach { child =>
        child.forward(msg)
      }
    case unknown =>
      log.warning(s"Unknown message from $sender: $unknown")
  }

  private def configProvider(
      keyManager: ActorRef)(implicit ec: ExecutionContext): ConfigProvider = new ConfigProvider {
    override def rktRunnerConfig =
      RktRunner.Config(
          Paths.get("/var/lib/dit4c-rkt"),
          "dit4c-instance",
          config.authImage,
          config.listenerImage,
          config.storageImage,
          config.uploadImage)
    override def sshKeys = {
      import akka.pattern.ask
      implicit val timeout = Timeout(1.minute)
      (keyManager ? KeyManager.GetOpenSshKeyPairs)
        .collect {
          case KeyManager.OpenSshKeyPairs(pairs) =>
            pairs
        }
    }

  }
}

