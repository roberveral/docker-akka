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
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
  * Companion object for a ServiceWorker. It contains the methods for creating a new ServiceWorker actor
  * and the message definition allowed by a ServiceWorker actor.
  *
  * @author Rober Veral (roberveral@gmail.com)
  * @see com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
  */
object ServiceWorker {
  /**
    * Get the props for creating a ServiceWorker actor.
    *
    * @param service the DockerService that will be executed in the ServiceWorker actor
    * @param timeout timeout used for waiting for requests and futures
    * @return a Props for creating the actor with the given parameters
    */
  def props(service: DockerService)(implicit timeout: Timeout): Props = Props(new ServiceWorker(service))

  /**
    * Message for telling an available work to do
    *
    * @param serviceName name of the service available
    * @param master      ServiceMaster actor that performs the offer
    */
  case class Service(serviceName: String, master: ActorRef)

  /**
    * Message for give a Service task to the worker
    *
    * @param service service to run as container
    */
  case class ServiceTask(service: DockerService)

  /**
    * Message for getting task info
    */
  case object Info

  /**
    * Message for info response
    *
    * @param info string with info about the container running in the actor
    */
  case class TaskInfo(info: String)

  /**
    * Message for no work remaining in the master when a ServiceAccepted message
    * is sent to the ServiceMaster associated
    */
  case object NoTasksAvailable

  /**
    * Message for cancelling the current task
    */
  case object Cancel

  /**
    * Message for starting the container
    */
  case object Start

  /**
    * Message for an exception that occurs in the running container.
    *
    * @param e exception that raises this message
    */
  case class DockerError(e: Exception)

}

/**
  * A ServiceWorker actor performs the execution and management of a service starting its
  * Docker container. Each ServiceWorker is related to one ServiceMaster and, given that, a
  * concrete DockerService.
  * When starting, the ServiceWorker will check if there is a previous container running for
  * the service in the worker node where it run, and in that case it will attach to that
  * container, avoiding stopping the service if something internal is restarted.
  * If the master don't give a ServiceWorker an instance to run, if any previous instance was
  * running will be finished and removed for retrieving garbage containers.
  *
  * @param service the DockerService that will be executed in the ServiceWorker actor
  * @param timeout timeout used for waiting for requests and futures
  * @author Rober Veral (roberveral@gmail.com)
  * @see com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
  */
class ServiceWorker(service: DockerService)(implicit timeout: Timeout) extends Actor with ActorLogging {

  import ServiceWorker._
  import context._

  // Keep track of the docker client
  val client: Option[DefaultDockerClient] = Some(new DefaultDockerClient("unix:///var/run/docker.sock"))
  // Keep track of the container created. Check if a previous container exists and get id
  var containerId: Future[Option[String]] = getContainerId(client.get, service.name)
  // Set a timeout awaiting for communication from the master
  setReceiveTimeout(60 seconds)

  /**
    * Gets the unique worker name based in the hostname and port defined in the configuration
    *
    * @return the worker name (hostname:port)
    */
  private def getWorkerName = {
    val hostname = context.system.settings.config.getString("akka.remote.netty.tcp.hostname")
    val port = context.system.settings.config.getString("akka.remote.netty.tcp.port")
    s"$hostname:$port"
  }

  /**
    * Gets the container id of a previously running container.
    *
    * @param dockerClient the client to connect with Docker
    * @param serviceName  the name of the service the ServiceWorker is running
    * @return a Future with an Option that may contain the id of a running container of None if it don't exist
    */
  private def getContainerId(dockerClient: DefaultDockerClient, serviceName: String) = {
    // Get previous containers with the correct name
    Future {
      val previous = dockerClient.listContainers(ListContainersParam.allContainers()).toList.
        filter(_.names().contains(s"/$serviceName-${getWorkerName.hashCode}"))
      previous match {
        case Nil => None
        case x :: _ => Some(x.id())
      }
    }
  }

  /**
    * Removes a container from Docker
    *
    * @param dockerClient client for connecting with Docker
    * @param container    container id to remove
    */
  private def removeContainer(dockerClient: DefaultDockerClient, container: String) = {
    // Kill the running container
    dockerClient.killContainer(container)
    // Remove the container
    dockerClient.removeContainer(container)
    log.info(s"Removed container from service ${service.name}")
  }

  override def receive: Receive = idle

  /**
    * Method for receiving messages in idle state.
    * In this state, the worker is wating for an offer of the ServiceMaster
    * to run a service instance.
    *
    * @return Receive
    */
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

    case ReceiveTimeout =>
      // Garbage collection of former running containers
      containerId.map(_.foreach(id => client.foreach(dockerClient => removeContainer(dockerClient, id))))
      containerId = Future {
        None
      }
      // Remove timeout
      setReceiveTimeout(Duration.Inf)
  }

  /**
    * Method for receiving messages in ready state.
    * In this state, the worker is waiting for a task (a instance to run) from the master
    * once the worker has acknowledged that is able to run a ServiceMaster offer.
    * It's possible that the master don't have any instance available at the moment.
    *
    * @param name   name of the service
    * @param master ServiceMaster that send the service offer
    * @return Receive
    */
  def ready(name: String, master: ActorRef): Receive = {
    // In ready state, worker is waiting for the task to perform
    case ReceiveTimeout =>
      master ! ServiceMaster.ServiceAccepted(name, self)

    case ServiceTask(_) =>
      // Prepare Docker configuration
      // Initializes a DockerClient to comunicate with the Docker Daemon in the host
      val dockerClient = client.getOrElse(new DefaultDockerClient("unix:///var/run/docker.sock"))

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

      // Start the container
      containerId = containerId.map(container => {
        // If container is not created previously, create a new one
        if (container.isEmpty) {
          // Obtains the images (or check if exists)
          dockerClient.pull(service.image)
          // Creates new container
          Some(dockerClient.createContainer(containerConfig, s"${service.name}-${getWorkerName.hashCode}").id())
        }
        else container
      })
      // Start the container
      self ! Start
      // Change to running state
      become(running(master))
      log.info(s"Worker Running Service $name from $master. Switching to RUNNING")

    case NoTasksAvailable =>
      log.info(s"No tasks available for service $name from $master. Ending worker")
      self ! Kill
  }

  /**
    * Method for receiving messages in the running state.
    * In this state the ServiceMaster has given an instance to the worker and
    * the container is running.
    *
    * @param master ServiceMaster that sent the service offer
    * @return Receive
    */
  def running(master: ActorRef): Receive = {
    // Launches Docker Container
    case Start =>
      client.foreach(dockerClient =>
        containerId.map(container => container.map((id) => {
          // Starts the container (avoiding failure in case it is started)
          Try(dockerClient.startContainer(id))
          // Attach to its result
          val res = dockerClient.waitContainer(id)
          // In case of error throw a Exception
          if (res.statusCode() != 0) throw new RuntimeException("StatusCode: " + res.statusCode())
          else res
        })).onFailure {
          // In case of failure, a DockerError message is sended to the actor to throw the failure
          // from the main thread, allowing supervision
          case e: Exception => self ! DockerError(e)
        })

    case Info =>
      client.foreach(dockerClient => {
        val info = Await.result(containerId.map(_.map((id) => {
          // Gets information detail of the container by running Docker inspect
          dockerClient.inspectContainer(id)
        })), timeout.duration)
        log.info(s"Worker ${service.name} sending status")
        // Returns information to the sender
        sender() ! TaskInfo(info.toString)
      })


    case DockerError(e) =>
      // Remove container info
      containerId = Future {
        None
      }
      // Throw the exception (let it crash)
      log.error("Worker for {} execution failed.", service.name)
      // Send the ServiceMaster a fail message
      master ! WorkerFailed(self)
      throw e

    case Cancel =>
      // Await for container destroy
      Await.result(containerId.map(_.foreach(id => client.foreach(dockerClient => removeContainer(dockerClient, id)))),
        timeout.duration)
      // Remove container info
      containerId = Future {
        None
      }
      log.info("{} destroyed.", service.name)
      // Finally, kill the actor
      self ! Kill
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    // Remove running container (if exists)
    //containerId.map(_.foreach(id => client.foreach(dockerClient => removeContainer(dockerClient, id))))
    // Ensure that DockerClient is stopped
    client.foreach(_.close())
  }
}