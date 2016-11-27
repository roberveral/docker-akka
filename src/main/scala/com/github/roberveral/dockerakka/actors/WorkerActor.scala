package com.github.roberveral.dockerakka.actors

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, Props}
import akka.util.Timeout
import com.github.roberveral.dockerakka.actors.WorkerActor.Launch
import com.github.roberveral.dockerakka.utils.DockerService

/**
  * The WorkerActor is the main actor in a Worker node. It
  * launches services in this node based on the messages
  * sended by the MasterActor.
  *
  * Created by roberveral on 27/11/16.
  */
object WorkerActor {
  def props(implicit timeout: Timeout) = Props(new WorkerActor)

  def name = "worker"

  // Message used for launching services
  case class Launch(service: DockerService)

}

/**
  * The WorkerActor is the main actor in a Worker node. It
  * launches services in this node based on the messages
  * sended by the MasterActor.
  *
  * @param timeout timeout for asynchronous requests.
  */
class WorkerActor(implicit timeout: Timeout) extends Actor with ActorLogging {

  import context._

  override def receive: Receive = {
    case Launch(service) => {
      def create(): Unit = {
        // Creates a new ServiceActor
        actorOf(ServiceActor.props(service), service.name)
        log.info("{} created.", service.name)
      }

      // If the service already exists, is not created, in other case
      // a new child ServiceActor is created
      child(service.name).fold(create())((_) => log.error("{} already exists.", service.name))
    }
  }
}