package com.github.roberveral.dockerakka.cluster.master

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, DeathPactException, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker.TaskInfo
import com.github.roberveral.dockerakka.utils.DockerService

/**
  * Created by roberveral on 6/12/16.
  */
object ServiceScheduler {
  def props(implicit timeout: Timeout): Props = Props(new ServiceScheduler())
  def name: String = "scheduler"
  // Message API definition
  // Creates and launches a new DockerService in the system.
  case class Create(service: DockerService, instances: Int)

  // Scales a running DockerService.
  case class Scale(name: String, num: Int)

  // Destroys a running DockerService
  case class Destroy(name: String)

  // Gets the info of a running service
  case class Status(name: String)

  // Message used to get info of all running services
  case object Status

  // Responses
  sealed trait Response
  // Service successfully created
  case class ServiceCreated(name: String) extends Response
  // Service operation succeeded
  case class ServiceOk(name: String) extends Response
  // Service already exists
  case class ServiceExists(name: String) extends Response
  // Service creation failed
  case class ServiceFailed(name: String) extends Response
  // Service list response
  case class ServiceList(services: List[String]) extends Response
  // Service Status response
  case class ServiceStatus(instances: List[TaskInfo]) extends Response
}

class ServiceScheduler(implicit timeout: Timeout) extends Actor with ActorLogging with CreateMaster {
  import ServiceScheduler._
  import context._

  // Overrides SupervisionStrategy to change to restart strategy.
  // If a ServiceMaster goes down, it will be restarted by handling termination message.
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ =>
      log.info("master restarted")
      Restart
  }

  override def receive: Receive = {
    case Create(service, instances) =>
      def create(): Unit = {
        // Create ServiceMaster actor
        val master: ActorRef = createMaster(service)
        // Forward the creation message to the master
        master forward ServiceMaster.StartService(instances)
      }
      // Check if service exists or create it
      child(service.name).fold(create)(_ => sender() ! ServiceExists(service.name))

    case Scale(name, num) =>
      child(name).fold(sender() ! ServiceFailed(name))(_ forward ServiceMaster.ScaleService(num))

    case Destroy(name) =>
      child(name).fold(sender() ! ServiceFailed(name))(_ forward ServiceMaster.StopService)

    case Status(name) =>
      log.info(s"Asking status of service $name")
      child(name).fold(sender() ! ServiceFailed(name))(_ forward ServiceMaster.Info)

    case Status => sender() ! ServiceList(children.map(_.path.name).toList)
  }
}

trait CreateMaster {
  def context: ActorContext
  def createMaster(service: DockerService)(implicit timeout: Timeout): ActorRef = context.actorOf(ServiceMaster.props(service), service.name)
}
