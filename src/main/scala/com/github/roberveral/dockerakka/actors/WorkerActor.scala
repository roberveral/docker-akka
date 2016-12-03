package com.github.roberveral.dockerakka.actors

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy}
import akka.util.Timeout
import com.github.roberveral.dockerakka.actors.WorkerActor.{AllInfo, Info, Launch, Stop}
import com.github.roberveral.dockerakka.utils.{DockerService, ServiceInfo}

import scala.collection.immutable.Iterable
import scala.concurrent.Future

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

  // Mesage used to stop running services
  case class Stop(name: String)

  // Mesage used to get info of running services
  case class Info(name: String)

  // Mesage used to get info of all running services
  case object AllInfo

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

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10) {
    case _ => {
      log.warning("Restarting child due to failure")
      Restart
    }
  }

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
    case Info(name) => child(name).fold(log.warning("{} Service doesn't exist.", name))(_ forward ServiceActor.Info)
    case AllInfo =>
      import akka.pattern.ask
      import akka.pattern.pipe
      // Gets info for all the childern services
      val childInfo: Iterable[Future[ServiceInfo]] = children.map(_.ask(ServiceActor.Info).mapTo[ServiceInfo])
      // Pipes the resulting list to the sender
      pipe(Future.sequence(childInfo)) to sender()
    case Stop(name) => child(name).fold(log.warning("{} Service doesn't exist.", name))(_ ! ServiceActor.Cancel)
  }
}