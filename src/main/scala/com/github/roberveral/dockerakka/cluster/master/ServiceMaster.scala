package com.github.roberveral.dockerakka.cluster.master

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ActorContext, ActorLogging, ActorRef, Kill, OneForOneStrategy, PoisonPill, Props, ReceiveTimeout, SupervisorStrategy, Terminated}
import akka.cluster.metrics.{AdaptiveLoadBalancingPool, MixMetricsSelector}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.PersistentActor
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker.TaskInfo
import com.github.roberveral.dockerakka.utils.DockerService

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by roberveral on 6/12/16.
  */
object ServiceMaster {
  def props(implicit timeout: Timeout) = Props(new ServiceMaster())

  // Properties for sharding
  val shardType: String = "masters"
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: ServiceMaster.Command => (cmd.serviceName, cmd)
  }
  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: ServiceMaster.Command => (cmd.serviceName.hashCode() % 100).toString
  }

  // Commands API
  sealed trait Command {
    def serviceName: String
  }

  // Start the Service
  case class StartService(serviceName: String, service: DockerService, instances: Int) extends Command

  // Scale the service
  case class ScaleService(serviceName: String, instances: Int) extends Command

  // Get service info
  case class Info(serviceName: String) extends Command

  // Stop the service
  case class StopService(serviceName: String) extends Command

  // Worker accepted task message
  case class ServiceAccepted(serviceName: String, worker: ActorRef) extends Command

  case class WorkerFailed(worker: ActorRef)

  // Events API (events are stored in persisted storage as event journal)
  sealed trait Event

  // Start of service event
  case class ServiceStarted(service: DockerService, instances: Int) extends Event

  // Scale service event
  case class ServiceScaled(instances: Int) extends Event

  // Stop event service
  case object ServiceStopped extends Event

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

class ServiceMaster(implicit timeout: Timeout) extends PersistentActor with ActorLogging with CreateWorkerRouter {

  import ServiceMaster._
  import context._

  // Keep the Docker Service
  var service: Option[DockerService] = None
  // Keep track of the instances of the service
  var instances: Int = 0
  // Keep track of registered workers
  var workers: Set[ActorRef] = Set[ActorRef]()

  // Create the router to send job to the workers
  val router: ActorRef = createWorkerRouter

  // Initial timeout for detecting idle masters in sharding and removing them for memory saving
  context.setReceiveTimeout(120 seconds)

  // Sets stoppingStrategy, a dead instances will be replaced in the cluster
  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy


  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Unwatch workers
    workers.foreach(unwatch)
    // Resend Start message
    if (service.isDefined) self ! StartService(service.get.name, service.get, instances)
    super.preRestart(reason, message)
  }

  // Method for updating the Master state, based on the events that affects each master
  private val updateState: Event => Unit = {
    case ServiceStarted(dservice, num) =>
      service = Some(dservice)
      instances = num
      // Sends creation messages for each instance to the router
      (1 to instances) foreach (_ => context.system.scheduler.scheduleOnce(1000 millis, router, ServiceWorker.Service(service.get.name, self)))
      // Sets timeout for waiting for instances to join
      context.setReceiveTimeout(timeout.duration)
      // Finally, change to working state
      become(working(sender()))
    case ServiceScaled(num) =>
      instances = num
    case ServiceStopped =>
  }

  def idle: Receive = {
    // In Idle state, the Master is waiting for an incoming Job
    case StartService(_, dservice, num) =>
      // Persist the event in the journal and update the state
      persist(ServiceStarted(dservice, num))(updateState)
      // Reply sender that the Service is created
      sender() ! ServiceCreated(dservice.name)
      log.info(s"Starting service ${dservice.name}. Switching to WORKING")

    case _: ServiceMaster.Command => sender() ! ServiceFailed("")

    case ReceiveTimeout =>
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def working(creator: ActorRef): Receive = {
    case StartService(_, _, _) =>
      sender() ! ServiceExists(service.get.name)

    case ServiceAccepted(_, worker) =>
      if (workers.size == instances)
        worker ! ServiceWorker.NoTasksAvailable
      else {
        // Add the worker to the worker list
        workers = workers + worker
        // Monitor worker for termination
        watch(worker)
        // Send worker task to perform
        worker ! ServiceWorker.ServiceTask(service.get)
        log.info(s"Worker accepted task for ${service.get.name}")
      }

    case ScaleService(_, num) =>
      if (num > instances) {
        // Send new tasks to router
        (workers.size to num) foreach (_ => router ! ServiceWorker.Service(service.get.name, self))
        // Sets timeout for waiting for instances to join
        context.setReceiveTimeout(timeout.duration)
      } else if (num < instances) {
        // See how many instances to kill (scale-down)
        val kill = workers.drop(num)
        // Kill the instances and update the state
        kill.foreach(unwatch)
        kill.foreach(_ ! ServiceWorker.Cancel)
        workers = workers -- kill
      }
      // Persist the event in the journal and update the state
      persist(ServiceScaled(num))(updateState)
      sender() ! ServiceOk(service.get.name)
      log.info(s"Service ${service.get.name} scaled to $num instances")

    case StopService(_) =>
      // Persist the event in the journal and update the state
      persist(ServiceStopped)(updateState)
      // Unwatch and end all the workers
      workers foreach unwatch
      // End all the workers
      workers foreach (_ ! ServiceWorker.Cancel)
      // Send an OK response
      sender() ! ServiceOk(service.get.name)
      // End the master
      self ! PoisonPill
      log.info(s"Service ${service.get.name} stopped")

    case Info(_) =>
      // Get the info for all the workers
      import akka.pattern.ask
      import akka.pattern.pipe
      log.info(s"master for service ${service.get.name} requesting status")
      // Gets info for all the childern services
      val childInfo =
        workers.map(worker => {
          Await.result(worker.ask(ServiceWorker.Info).mapTo[ServiceWorker.TaskInfo], timeout.duration)
        })
      sender() ! ServiceStatus(childInfo.toList)

    case ReceiveTimeout =>
      if (workers.isEmpty) {
        log.error(s"No workers accepted tasks on time for service ${service.get.name}. Ending master")
        // Sends ServiceFailed to the creator
        creator ! ServiceFailed(service.get.name)
        // Ends itself due to inability to get resources
        self ! Kill
      } else if (workers.size < instances) {
        log.warning(s"Not enough resources for all the tasks for service ${service.get.name}. Resending work messages to workers")
        // Send another time remaining instances notification
        (workers.size to instances) foreach (_ => router ! ServiceWorker.Service(service.get.name, self))
      } else
        context.setReceiveTimeout(Duration.Inf)

    case Terminated(worker) =>
      log.warning(s"Worker $worker terminated unexpectly for service ${service.get.name}")
      // Remove worker from workers set
      workers = workers - worker
      // Sets timeout for waiting for instances to join
      context.setReceiveTimeout(timeout.duration)
      // Ask for a new worker to the router
      router ! ServiceWorker.Service(service.get.name, self)

    case WorkerFailed(worker) =>
      log.warning(s"Worker $worker terminated unexpectly for service ${service.get.name}")
      // Remove worker from workers set
      workers = workers - worker
      // Sets timeout for waiting for instances to join
      context.setReceiveTimeout(timeout.duration)
      // Ask for a new worker to the router
      router ! ServiceWorker.Service(service.get.name, self)

  }

  // Persistence methods

  // In recovering, the state is rebuilt from the journal
  override def receiveRecover: Receive = {
    case ServiceStopped =>
      // An Stop event destroys the actor, similar to come back to idle state
      become(idle)
    case cmd: Event => updateState(cmd)
  }

  // Normal receive function
  override def receiveCommand: Receive = idle

  // Identifier to persistence
  override def persistenceId: String = self.path.name
}

trait CreateWorkerRouter {
  def context: ActorContext

  def createWorkerRouter(implicit timeout: Timeout): ActorRef = {
    // The strategy for the router will be restart (to be sure that one worker is available in each
    // worker node in the cluster)
    val strategy = OneForOneStrategy() { case _ => Restart }
    // Creates a router that will balance the request using metrics of the node as placement strategy
    context.actorOf(
      ClusterRouterPool(AdaptiveLoadBalancingPool(MixMetricsSelector, supervisorStrategy = strategy),
        ClusterRouterPoolSettings(totalInstances = 1000, maxInstancesPerNode = 1,
          allowLocalRoutees = false, useRole = Some("worker"))).props(ServiceWorker.props),
      name = "worker-router")
  }
}