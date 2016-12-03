package com.github.roberveral.dockerakka.actors

import java.util

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import com.github.roberveral.dockerakka.actors.ServiceActor.{Cancel, DockerError, Info}
import com.github.roberveral.dockerakka.utils.{DockerService, ServiceInfo}
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.{ContainerConfig, ContainerCreation, HostConfig, PortBinding}

import collection.JavaConverters._
import scala.concurrent.Future

/**
  * Actor that manages a service launched as a Docker container.
  * It manages the container execution and status.
  *
  * Created by roberveral on 27/11/16.
  */
object ServiceActor {
  def props(service: DockerService) = Props(new ServiceActor(service))

  // Cancel Message
  case object Cancel

  // Info Message
  case object Info

  // Docker error message
  case class DockerError(e: Exception)

}

/**
  * Actor that manages a service launched as a Docker container.
  * It manages the container execution and status.
  *
  * @param service docker service definition.
  */
class ServiceActor(service: DockerService) extends Actor with ActorLogging {

  import context._

  // Initializes a DockerClient to comunicate with the Docker Daemon in the host
  val dockerClient = new DefaultDockerClient("unix:///var/run/docker.sock");

  // Obtains the port configuration based in the port mapping of the service
  val portConfig: Map[String, util.List[PortBinding]] =
    service.portMapping.map { case (host, container) => (container.toString, List(PortBinding.of("0.0.0.0", host)).asJava) }

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

  // Launch the service in Docker as a Scala Future
  launchService()

  def launchService(): Unit = {
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
  }

  override def receive: Receive = {
    case Cancel => {
      containerCreation.map((container) => {
        // Kill the running container
        dockerClient.killContainer(container.id())

        // Remove the container
        dockerClient.removeContainer(container.id())
      }).onComplete(_ => {
        log.info("{} destroyed.", service.name)
        // Finally, kill the actor
        self ! PoisonPill
      })
    }
    case Info => {
      containerCreation.map((container) => {
        // Gets information detail of the container by running Docker inspect
        val info = dockerClient.inspectContainer(container.id()).toString
        log.info("Info response: {}", ServiceInfo(service, info))
        // Returns information to the sender
        sender ! ServiceInfo(service, info)
      })
    }
    case DockerError(e) => {
      // Throw the exception (let it crash)
      log.error("{} execution failed.", service.name)
      throw e
    }
    case _ => log.info("{} container currently launched.", service.name)
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    dockerClient.close()
    super.postStop()
  }
}