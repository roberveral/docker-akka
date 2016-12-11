package com.github.roberveral.dockerakka.cluster.worker

import java.net.{Inet4Address, InetAddress}
import java.util

import akka.actor.{Actor, ActorLogging, ActorRef, Kill, Props, ReceiveTimeout}
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.master.ServiceMaster
import com.github.roberveral.dockerakka.cluster.master.ServiceMaster.WorkerFailed
import com.github.roberveral.dockerakka.utils.DockerService
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.ListContainersParam
import com.spotify.docker.client.messages._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

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

  case object Attach

  // Docker error message
  case class DockerError(e: Exception)

}

class ServiceWorker(implicit timeout: Timeout) extends Actor with ActorLogging {

  import ServiceWorker._
  import context._

  log.info(s"creating worker ${self.path}")
  // Keep track of the docker client
  var client: Option[DefaultDockerClient] = None
  // Keep track of the container created
  var containerId: Option[Future[String]] = None

  private def getWorkerName = {
    val hostname = context.system.settings.config.getString("akka.remote.netty.tcp.hostname")
    val port = context.system.settings.config.getString("akka.remote.netty.tcp.port")
    s"$hostname:$port"
  }

  override def receive: Receive = idle

  def idle: Receive = {
    // In Idle state, worker is waiting for work offers
    case Service(name, master) =>
      // Change state to running
      become(ready(name, master))
      // Tell Master that the offer is accepted
      master ! ServiceMaster.ServiceAccepted(name, self)
      // Monitor master
      watch(master)
      // Set a timeout awaiting for tasks to perform
      setReceiveTimeout(timeout.duration)
      log.info(s"Worker Accepted Service $name from $master. Switching to READY")
  }

  def ready(name: String, master: ActorRef): Receive = {
    // In ready state, worker is waiting for the task to perform
    case ReceiveTimeout =>
      master ! ServiceMaster.ServiceAccepted(name, self)

    case ServiceTask(service) =>
      // Prepare Docker configuration
      // Initializes a DockerClient to comunicate with the Docker Daemon in the host
      val dockerClient = new DefaultDockerClient("unix:///var/run/docker.sock")
      client = Some(dockerClient)

      // Obtains the port configuration based in the port mapping of the service
      val portConfig: Map[String, util.List[PortBinding]] =
        service.ports.map { port => (port.toString, List(PortBinding.randomPort("0.0.0.0")).asJava) }.toMap

      // Creates the Host configuration for the container with the ports configuration
      val hostConfig: HostConfig =
        HostConfig.builder()
          .portBindings(portConfig.asJava).build()

      // Configures the container to launch based on the service.
      val containerConfig: ContainerConfig =
        ContainerConfig.builder()
          .image(service.image)
          .exposedPorts(service.ports.map(_.toString).toSet.asJava)
          .hostConfig(hostConfig).build()

      // Check if a previous container exists and get id
      val previous = dockerClient.listContainers(ListContainersParam.allContainers()).toList.
        filter(_.names().contains(s"/${service.name}-${getWorkerName.hashCode}"))

      if (previous.isEmpty) {
        // Creates the container of the service
        val container: Future[String] = Future {
          // Obtains the images (or check if exists)
          dockerClient.pull(service.image)
          // Creates new container
          dockerClient.createContainer(containerConfig, s"${service.name}-${getWorkerName.hashCode}").id()
        }
        containerId = Some(container)
        // Autosend Start message
        self ! Start
        // Discard timeout
        setReceiveTimeout(Duration.Inf)
      } else {
        containerId = Some(Future {
          previous.head.id()
        })
        self ! Attach
      }
      // Change to running state
      become(running(dockerClient, service, containerId.get, master))
      log.info(s"Worker Running Service $name from $master. Switching to RUNNING")

    case NoTasksAvailable =>
      log.info(s"No tasks available for service $name from $master. Ending worker")
      self ! Kill
  }

  def running(dockerClient: DefaultDockerClient,
              service: DockerService,
              container: Future[String],
              master: ActorRef): Receive = {
    // Launches Docker Container
    case Start =>
      container.map((id) => {
        // Starts the container
        dockerClient.startContainer(id)
        // Attach to its result
        val res = dockerClient.waitContainer(id)
        // In case of error throw a Exception
        if (res.statusCode() != 0) throw new RuntimeException("StatusCode: " + res.statusCode())
      }).onFailure {
        // In case of failure, a DockerError message is sended to the actor to throw the failure
        // from the main thread, allowing supervision
        case e: Exception => self ! DockerError(e)
      }

    // Attaches Docker Container
    case Attach =>
      container.map((id) => {
        // Attach to its result
        val res = dockerClient.waitContainer(id)
        // In case of error throw a Exception
        if (res.statusCode() != 0) throw new RuntimeException("StatusCode: " + res.statusCode())
      }).onFailure {
        // In case of failure, a DockerError message is sended to the actor to throw the failure
        // from the main thread, allowing supervision
        case e: Exception => self ! DockerError(e)
      }

    case Info =>
      val info = Await.result(container.map((id) => {
        // Gets information detail of the container by running Docker inspect
        dockerClient.inspectContainer(id)
      }), timeout.duration)
      log.info(s"Worker ${service.name} sending status")
      // Returns information to the sender
      sender() ! TaskInfo(info.toString)

    case DockerError(e) =>
      // Remove container info
      containerId = None
      // Throw the exception (let it crash)
      log.error("Worker for {} execution failed.", service.name)
      master ! WorkerFailed(self)
      throw e

    case Cancel =>
      Await.result(container.map((id) => {
        // Kill the running container
        dockerClient.killContainer(id)

        // Remove the container
        dockerClient.removeContainer(id)
      }), timeout.duration)
      // Remove container info
      containerId = None
      log.info("{} destroyed.", service.name)
      // Finally, kill the actor
      self ! Kill
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    containerId.foreach(_.map(id => {
      // Kill the running container
      client.get.killContainer(id)

      // Remove the container
      client.get.removeContainer(id)
    }))
    // Ensure that DockerClient is stopped
    client.foreach(_.close())
  }
}