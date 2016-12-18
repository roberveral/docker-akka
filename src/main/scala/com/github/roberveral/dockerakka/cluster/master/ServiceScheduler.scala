package com.github.roberveral.dockerakka.cluster.master

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.master.ServiceScheduler.Status

/**
  * Companion object for a ServiceScheduler. It contains the methods for creating a new ServiceScheduler actor
  * and the message definition allowed by a ServiceScheduler actor.
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
object ServiceScheduler {
  /**
    * Get the props for creating a ServiceScheduler actor.
    *
    * @param timeout timeout used for waiting for requests and futures
    * @return a Props for creating the actor with the given parameters
    */
  def props(implicit timeout: Timeout): Props = Props(new ServiceScheduler())

  /**
    * Gets the actor name for a ServiceScheduler
    *
    * @return ServiceScheduler actor name
    */
  def name: String = "scheduler"

  /**
    * Message used to get info of all running services
    */
  case object Status

}

/**
  * A ServiceScheduler actor is a proxy in front of the ServiceMaster delegating in the sharding
  * the deliver of the messages to the correct ServiceMaster actor
  *
  * @param timeout timeout used for waiting for requests and futures
  * @author Rober Veral (roberveral@gmail.com)
  */
class ServiceScheduler(implicit timeout: Timeout) extends Actor with ActorLogging {
  import context._
  // Initializes Cluster Sharding for creating Masters
  ClusterSharding(context.system).start(
    ServiceMaster.shardType,
    ServiceMaster.props,
    ClusterShardingSettings(context.system).withRole("master").withRememberEntities(true),
    ServiceMaster.extractEntityId,
    ServiceMaster.extractShardId
  )

  // Defines the shardedActor to talk with
  def shardedMaster: ActorRef = ClusterSharding(context.system).shardRegion(ServiceMaster.shardType)

  override def receive: Receive = {
    case msg: ServiceMaster.Command => shardedMaster forward msg
    case Status =>
      import akka.pattern.{ask, pipe}
      val state = (shardedMaster ? ShardRegion.GetShardRegionState).mapTo[ShardRegion.CurrentShardRegionState]
      pipe(state.map(_.shards.flatMap(_.entityIds).toList).map(ServiceMaster.ServiceList)) to sender()
  }
}
