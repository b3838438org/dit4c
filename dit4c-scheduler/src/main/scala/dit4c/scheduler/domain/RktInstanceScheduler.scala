package dit4c.scheduler.domain

import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import akka.actor.ActorLogging
import akka.actor.TypedActor.PostStop
import akka.actor.Cancellable

object RktInstanceScheduler {

  sealed trait Response extends BaseResponse
  case class WorkerFound(nodeId: String, worker: ActorRef) extends Response
  case object NoWorkersAvailable extends Response

}

class RktInstanceScheduler(
    instanceId: String,
    nodes: Map[String, ActorRef],
    completeWithin: FiniteDuration,
    requireWorker: Boolean) extends Actor with ActorLogging {

  import RktInstanceScheduler._
  private object TimedOut

  var cancelTimeout: Option[Cancellable] = None

  override def preStart = {
    implicit val ec = context.dispatcher
    sequentiallyCheckNodes(Random.shuffle(nodes.keys.toList))
    cancelTimeout = Some(context.system.scheduler.scheduleOnce(completeWithin, self, TimedOut))
  }

  override def postStop = {
    cancelTimeout.foreach(_.cancel)
  }

  override val receive: Receive = {
    case _ => // Never uses this handler - always set by check
  }

  def sequentiallyCheckNodes(remainingNodesToTry: List[String]) {
    remainingNodesToTry match {
      case nodeId :: rest =>
        // Ready to receive response
        context.become({
          case RktNode.WorkerCreated(worker) =>
            context.parent ! WorkerFound(nodeId, worker)
            log.info(s"Instance worker provided by $nodeId")
            context.stop(self)
          case TimedOut =>
            log.warning(
                s"Unable to assign instance worker within $completeWithin")
            giveUpAndStop
          case RktNode.UnableToProvideWorker(msg) =>
            sequentiallyCheckNodes(rest)
        })
        // Query next Node
        log.info(s"Requesting instance worker from node $nodeId")
        nodes(nodeId) ! nodeRequest(instanceId)
      case Nil =>
        log.warning(
            s"All nodes refused to provide an instance worker")
        giveUpAndStop
    }
  }

  def giveUpAndStop {
    context.parent ! NoWorkersAvailable
    context.stop(self)
  }

  private def nodeRequest(instanceId: String) =
    if (this.requireWorker) RktNode.RequireInstanceWorker(instanceId)
    else RktNode.RequestInstanceWorker(instanceId)

}