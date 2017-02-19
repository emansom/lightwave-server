package de.lightwave.shockwave.handler

import akka.actor.{Actor, ActorRef, Props}
import akka.io.Tcp.Write
import akka.util.{ByteString, Timeout}
import de.lightwave.rooms.engine.RoomEvent
import de.lightwave.rooms.engine.entities.EntityDirector.SpawnEntity
import de.lightwave.rooms.engine.entities.{EntityReference, EntityStance, RoomEntity}
import de.lightwave.rooms.engine.mapping.MapCoordinator.GetAbsoluteHeightMap
import de.lightwave.rooms.engine.mapping.RoomMap.StaticMap
import de.lightwave.services.pubsub.Broadcaster.Subscribe
import de.lightwave.shockwave.handler.RoomHandler.SetEntity
import de.lightwave.shockwave.io.protocol.messages._

class RoomHandler(connection: ActorRef, roomEngine: ActorRef) extends Actor {
  import context.dispatcher
  import akka.pattern._
  import scala.concurrent.duration._

  implicit val timeout = Timeout(5.seconds)

  var entity: Option[ActorRef] = None

  // Make this optional?
  (roomEngine ? SpawnEntity(EntityReference(1, "Steve"))).map {
    case entity: ActorRef => SetEntity(entity)
  } pipeTo self

  // Subscribe to room events
  roomEngine ! Subscribe(self)

  def messageReceive: Receive = {
    case GetHeightmapMessage => (roomEngine ? GetAbsoluteHeightMap).map {
      case map:IndexedSeq[_] => Write(HeightmapMessageComposer.compose(map.asInstanceOf[StaticMap[Double]]))
    } pipeTo connection
    case GetUsersMessage =>
      connection ! Write(EntityListMessageComposer.compose(Seq.empty))
    case GetObjectsMessage =>
      connection ! Write(PublicObjectsMessageComposer.compose())
      connection ! Write(FloorItemsMessageComposer.compose())
    case GetItemsMessage =>
      connection ! Write(WallItemsMessageComposer.compose())
    case GetUserStancesMessage =>
      roomEngine ! RoomEntity.GetRenderInformation
  }

  def eventReceive: Receive = {
    case e:RoomEvent => println(e)
  }

  override def receive: Receive = messageReceive orElse eventReceive orElse {
    case SetEntity(e) => entity = Some(e)

    // Render entity that was requested by "GetUserStancesMessage"
    // Buffer them?
    case (id: Int, reference: EntityReference, stance: EntityStance) =>
      connection ! Write(EntityListMessageComposer.compose(Seq((id, reference, stance.pos))) ++ EntityStanceMessageComposer.compose(id, stance))
  }
}

object RoomHandler {
  case class SetEntity(entity: ActorRef)

  def props(connection: ActorRef, roomEngine: ActorRef) = Props(classOf[RoomHandler], connection, roomEngine)
}
