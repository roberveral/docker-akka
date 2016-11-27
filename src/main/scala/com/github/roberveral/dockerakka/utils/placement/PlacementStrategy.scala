package com.github.roberveral.dockerakka.utils.placement

import akka.actor.ActorRef

/**
  * Defines a strategy to place a new service in a worker node.
  *
  * Created by roberveral on 27/11/16.
  */
trait PlacementStrategy {
  /**
    * Obtains an Actor worker in which to place a new service according to the Strategy.
    * It also returns the new PlacementStrategy to use (allowing changing states).
    *
    * @param workers list of available worker actors.
    * @return an Option[ActorRef] with the selected actor (or None if the service is not allowed)
    *         and a PlacementStrategy with the new strategy to use.
    */
  def getPlace(workers: Seq[ActorRef]): (Option[ActorRef], PlacementStrategy)
}
