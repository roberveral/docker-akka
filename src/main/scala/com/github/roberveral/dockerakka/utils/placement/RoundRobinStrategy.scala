package com.github.roberveral.dockerakka.utils.placement

import akka.actor.ActorRef

import scala.util.{Failure, Success, Try}

/**
  * A RoundRobinStrategy is a PlacementStrategy that balances the workers
  * using a Round Robin principle.
  *
  * @param startIndex index to start the algorithm
  *                   Created by roberveral on 27/11/16.
  */
class RoundRobinStrategy(startIndex: Int) extends PlacementStrategy {
  /**
    * Obtains an Actor worker in which to place a new service according to the Strategy.
    * It also returns the new PlacementStrategy to use (allowing changing states).
    *
    * @param workers list of available worker actors.
    * @return an Option[ActorRef] with the selected actor (or None if the service is not allowed)
    *         and a PlacementStrategy with the new strategy to use.
    */
  override def getPlace(workers: Seq[ActorRef]): (Option[ActorRef], PlacementStrategy) =
    Try(workers(startIndex % workers.size)) match {
      case Success(actor) => (Some(actor), new RoundRobinStrategy(startIndex + 1))
      case Failure(_) => (None, this)
    }
}
