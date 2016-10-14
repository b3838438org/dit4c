package domain

import scala.reflect._

import akka.actor.ActorLogging
import akka.persistence.fsm.LoggingPersistentFSM
import akka.persistence.fsm.PersistentFSM
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import domain.SchedulerAggregate._
import java.time.Instant
import pdi.jwt.JwtJson

object SchedulerAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Active extends State

  sealed trait Data
  case object NoData extends Data
  case object SchedulerInfo extends Data

  sealed trait Command
  case object Create extends Command
  case class VerifyJwt(token: String) extends Command

  sealed trait Response
  case object Ack extends Response
  sealed trait VerifyJwtResponse extends Response
  case object ValidJwt extends VerifyJwtResponse
  case class InvalidJwt(msg: String) extends VerifyJwtResponse

  sealed trait DomainEvent extends BaseDomainEvent
  case class Created(timestamp: Instant = Instant.now) extends DomainEvent

}

class SchedulerAggregate()
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  implicit val m: Materializer = ActorMaterializer()

  lazy val schedulerId = self.path.name
  override lazy val persistenceId: String = "Scheduler-" + self.path.name

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(Create, _) =>
      val requester = sender
      goto(Active).applying(Created()).andThen { _ =>
        requester ! Ack
      }
    case Event(VerifyJwt(_), _) =>
      stay replying InvalidJwt("Unknown scheduler")
  }

  when(Active) {
    case Event(VerifyJwt(token), _) =>
      val response = JwtJson.decode(token)
        .toOption.toRight("Failed to verify JWT without key")
        .right.flatMap { claim =>
          if (claim.isValid) Right(claim)
          else Left("Claim is not valid at this time")
        }
        .fold[VerifyJwtResponse](InvalidJwt(_), _ => ValidJwt)
      stay replying response
  }

  override def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Data = (domainEvent, currentData) match {
    case (Created(_), _) =>
      SchedulerInfo
    case p =>
      throw new Exception(s"Unknown state/data combination: $p")
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

}