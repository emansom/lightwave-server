package de.lightwave.rooms.engine.entity

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import de.lightwave.rooms.engine.entity.RoomEntity._
import de.lightwave.rooms.engine.entity.StanceProperty.WalkingTo
import de.lightwave.rooms.engine.mapping.MapCoordinator.{BlockTile, BlockTileTowardsDestination, ClearTile, GetHeight}
import de.lightwave.rooms.engine.mapping.{RoomDirection, Vector2, Vector3}
import de.lightwave.services.pubsub.Broadcaster.Publish

import scala.concurrent.duration._

case class EntityReference(id: Int, name: String)

trait EntityWalking { this: RoomEntity =>
  import context.dispatcher
  import akka.pattern._

  private var walkDestination: Option[Vector2] = None
  private var walking: Boolean = false

  private def finishWalk(): Unit = {
    stance = stance.copy(properties = stance.properties.without[WalkingTo])
    broadcastPosition()
    walkDestination = None
    walking = false
  }

  private def walkTo(destination: Vector2) = if (!destination.is(position)) {
    walkDestination = Some(destination)
    if (!walking) {
      walking = true
      (mapCoordinator ? BlockTileTowardsDestination(position, destination))(Timeout(2.seconds)).mapTo[Option[Vector3]].map {
        case Some(pos) => WalkOver(pos)
        case None => FinishWalk
      }.recover {
        case _ => FinishWalk
      } pipeTo self
    }
  }

  protected def walkingReceive: Receive = {
    case WalkTo(destination) => walkTo(destination)
    case WalkOver(pos) =>
      val movementDirection = RoomDirection.getMovementDirection(position, pos).getOrElse(stance.bodyDirection)
      stance = EntityStance(stance.properties.replace(WalkingTo(pos)), movementDirection, movementDirection)
      broadcastPosition()
      context.system.scheduler.scheduleOnce(RoomEntity.WalkingSpeed, self, WalkOn(pos))
    case WalkOn(newPosition) =>
      // Clear old position
      mapCoordinator ! ClearTile(position.x, position.y)
      position = newPosition
      walking = false
      walkDestination match {
        case Some(destination) if !destination.is(position) => walkTo(destination)
        case _ => finishWalk()
      }
    case FinishWalk => finishWalk()
  }
}

/**
  * Living object in a room that can be a player, bot or a pet.
  * It interacts using signs, chat messages, dances and moves.
  *
  * @param id Virtual id
  */
class RoomEntity(id: Int, var reference: EntityReference, val mapCoordinator: ActorRef, broadcaster: ActorRef) extends Actor with EntityWalking {
  import akka.pattern._
  import context.dispatcher

  var position: Vector3 = Vector3.empty
  var stance = RoomEntity.DefaultStance

  def broadcastPosition(): Unit = {
    broadcaster ! Publish(PositionUpdated(id, position, stance))
  }

  override def receive: Receive = walkingReceive orElse {
    case TeleportTo(pos) =>
      (mapCoordinator ? GetHeight(pos.x, pos.y))(Timeout(2.seconds)).mapTo[Option[Vector3]].map {
        case Some(newPos) => SetPosition(newPos)
        case None => SetPosition(pos)
      }.recover {
        case _ => SetPosition(pos)
      } pipeTo self
    case SetPosition(pos) =>
      position = pos
      broadcastPosition()
    case GetRenderInformation => sender() ! RenderInformation(id, reference, position, stance)
    case GetPosition => sender() ! position
  }
}

object RoomEntity {
  val DefaultStance = EntityStance(properties = Set.empty,
    headDirection = RoomDirection.South,
    bodyDirection = RoomDirection.South)

  val WalkingSpeed: FiniteDuration = 500.milliseconds - 1.millisecond

  case object GetRenderInformation
  case object GetPosition

  case class TeleportTo(pos: Vector2)
  case class SetPosition(pos: Vector3)

  /**
    * Start walking to specific tile, if already walking
    * change destination
    */
  case class WalkTo(dest: Vector2)

  /**
    * Walk from one tile to another, that should
    * already be blocked
    */
  case class WalkOver(pos: Vector3)

  /**
    * Step onto new tile and finally leave old position.
    * If destination is not reached, continue walking.
    */
  case class WalkOn(newPos: Vector3)

  /**
    * Stop and finish off walk.
    */
  case object FinishWalk

  case class RenderInformation(virtualId: Int, reference: EntityReference, position: Vector3, stance: EntityStance)

  case class PositionUpdated(id: Int, pos: Vector3, stance: EntityStance) extends EntityEvent
  case class Spawned(id: Int, reference: EntityReference, entity: ActorRef)

  def props(id: Int, reference: EntityReference)(mapCoordinator: ActorRef, broadcaster: ActorRef) =
    Props(classOf[RoomEntity], id, reference, mapCoordinator, broadcaster)
}