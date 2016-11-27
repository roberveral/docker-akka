package com.github.roberveral.dockerakka.actors

import java.util

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import com.github.roberveral.dockerakka.utils.DockerService
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.{ContainerConfig, HostConfig, PortBinding}

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

  // Launch the service in Docker as a Scala Future
  launchService()

  def launchService(): Unit = {
    Future {
      // Obtains the images (or check if exists)
      dockerClient.pull(service.image)
      // Creates the container of the service
      val containerCreation = dockerClient.createContainer(containerConfig, service.name)
      // Starts the container
      dockerClient.startContainer(containerCreation.id())
      // Attach to its result
      val res = dockerClient.waitContainer(containerCreation.id())
      // In case of error throw a Exception
      if (res.statusCode() != 0) throw new RuntimeException("StatusCode: " + res.statusCode())
    }.onFailure {
      case e => {
        // In case of failure the Actor kills itself
        log.error("{} failed. Reason: {}", service.name, e.getMessage)
        self ! PoisonPill
      }
    }
  }

  override def receive: Receive = {
    case _ => log.info("{} container currently launched.", service.name)
  }
}