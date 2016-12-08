package com.github.roberveral.dockerakka.cluster.worker

import java.util

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, ReceiveTimeout}
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.master.ServiceMaster
import com.github.roberveral.dockerakka.utils.DockerService
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages._

import scala.concurrent.{Await, Future}
import collection.JavaConverters._
import scala.concurrent.duration.Duration

/**
  * Created by roberveral on 6/12/16.
  */
object ServiceWorker {
  def props(implicit timeout: Timeout): Props = Props(new ServiceWorker())

  // Message API
  // Message for telling an available work
  case class Service(serviceName: String, master: ActorRef)

  // Message for give a Service task to the worker
  case class ServiceTask(service: DockerService)

  // Message for getting task info
  case object Info

  // Message for info response
  case class TaskInfo(info: String)

  // Message for no work remaining
  case object NoTasksAvailable

  // Message for cancelling the current task
  case object Cancel

  // Message for starting the container
  case object Start

  // Docker error message
  case class DockerError(e: Exception)

}

class ServiceWorker(implicit timeout: Timeout) extends Actor with ActorLogging {

  import ServiceWorker._
  import context._

  log.info("creating worker")
  // Keep track of the docker client
  var client: Option[DefaultDockerClient] = None
  // Keep track of the container created
  var container: Option[Future[ContainerCreation]] = None


  override def receive: Receive = idle

  def idle: Receive = {
    // In Idle state, worker is waiting for work offers
    case Service(name, master) =>
      log.info("received service")
      // Change state to running
      become(ready(name, master))
      // Tell Master that the offer is accepted
      master ! ServiceMaster.ServiceAccepted(self)
      // Monitor master
      watch(master)
      // Set a timeout awaiting for tasks to perform
      setReceiveTimeout(timeout.duration)
      log.info(s"Worker Accepted Service $name from $master. Switching to READY")

    case _ => log.info("received anything else")
  }

  def ready(name: String, master: ActorRef): Receive = {
    // In ready state, worker is waiting for the task to perform
    case ReceiveTimeout =>
      master ! ServiceMaster.ServiceAccepted(self)

    case ServiceTask(service) =>
      // Prepare Docker configuration
      // Initializes a DockerClient to comunicate with the Docker Daemon in the host
      val dockerClient = new DefaultDockerClient("unix:///var/run/docker.sock")
      client = Some(dockerClient)

      // Obtains the port configuration based in the port mapping of the service
      val portConfig: Map[String, util.List[PortBinding]] =
        service.portMapping.map { case (host, cont) => (cont.toString, List(PortBinding.of("0.0.0.0", host)).asJava) }

      // Creates the Host configuration for the container with the ports configuration
      val hostConfig: HostConfig =
        HostConfig.builder()
          .portBindings(portConfig.asJava).build()

      // Configures the container to launch based on the service.
      val containerConfig: ContainerConfig =
        ContainerConfig.builder()
          .image(service.image)
          .exposedPorts(service.portMapping.values.map(_.toString).toSet.asJava)
          .hostConfig(hostConfig).build()

      // Creates the container of the service
      val containerCreation: Future[ContainerCreation] = Future {
        // Obtains the images (or check if exists)
        dockerClient.pull(service.image)
        dockerClient.createContainer(containerConfig, service.name)
      }
      container = Some(containerCreation)
      // Change to running state
      become(running(dockerClient, service, containerCreation))
      // Autosend Start message
      self ! Start
      // Discard timeout
      setReceiveTimeout(Duration.Inf)
      log.info(s"Worker Running Service $name from $master. Switching to RUNNING")

    case NoTasksAvailable =>
      log.info(s"No tasks available for service $name from $master. Ending worker")
      self ! PoisonPill
  }

  def running(dockerClient: DefaultDockerClient,
              service: DockerService,
              containerCreation: Future[ContainerCreation]): Receive = {
    // Launches Docker Container
    case Start =>
      containerCreation.map((container) => {
        // Starts the container
        dockerClient.startContainer(container.id())
        // Attach to its result
        val res = dockerClient.waitContainer(container.id())
        // In case of error throw a Exception
        if (res.statusCode() != 0) throw new RuntimeException("StatusCode: " + res.statusCode())
      }).onFailure {
        // In case of failure, a DockerError message is sended to the actor to throw the failure
        // from the main thread, allowing supervision
        case e: Exception => self ! DockerError(e)
      }

    case Info =>
      val info = Await.result(containerCreation.map((container) => {
        // Gets information detail of the container by running Docker inspect
        dockerClient.inspectContainer(container.id())
      }), timeout.duration)
      log.info(s"Worker ${service.name} sending status")
      // Returns information to the sender
      sender() ! TaskInfo(info.toString)

    case DockerError(e) =>
      // Remove container info
      container = None
      // Throw the exception (let it crash)
      log.error("Worker for {} execution failed.", service.name)
      throw e

    case Cancel =>
      Await.result(containerCreation.map((container) => {
        // Kill the running container
        dockerClient.killContainer(container.id())

        // Remove the container
        dockerClient.removeContainer(container.id())
      }), timeout.duration)
      // Remove container info
      container = None
      log.info("{} destroyed.", service.name)
      // Finally, kill the actor
      self ! PoisonPill
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    // Ensure that container is finished
    container.foreach(containerCreation => {
      Await.result(containerCreation.map((container) => {
        // Kill the running container
        client.get.killContainer(container.id())

        // Remove the container
        client.get.removeContainer(container.id())
      }), timeout.duration)
      log.info("{} destroyed.", self.path.name)
    })
    // Ensure that DockerClient is stopped
    client.foreach(_.close())
  }
}