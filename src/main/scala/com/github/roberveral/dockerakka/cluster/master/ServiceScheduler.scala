package com.github.roberveral.dockerakka.cluster.master

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.util.Timeout

/**
  * Created by roberveral on 6/12/16.
  */
object ServiceScheduler {
  def props(implicit timeout: Timeout): Props = Props(new ServiceScheduler())
  def name: String = "scheduler"
  // Message API definition
  // Message used to get info of all running services
  case object Status
}

class ServiceScheduler(implicit timeout: Timeout) extends Actor with ActorLogging {

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
  }
}
