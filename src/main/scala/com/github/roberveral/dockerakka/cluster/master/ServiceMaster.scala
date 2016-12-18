package com.github.roberveral.dockerakka.cluster.master

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ActorContext, ActorLogging, ActorRef, Kill, OneForOneStrategy, PoisonPill, Props, ReceiveTimeout, SupervisorStrategy, Terminated}
import akka.cluster.metrics.{AdaptiveLoadBalancingPool, MixMetricsSelector}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker.TaskInfo
import com.github.roberveral.dockerakka.utils.DockerService

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Companion object for a ServiceMaster. . It contains the methods for creating a new ServiceMaster actor
  * and the message definition allowed by a ServiceMaster actor.
  *
  * @author Rober Veral (roberveral@gmail.com)
  * @see com.github.roberveral.dockerakka.cluster.master.ServiceMaster
  */
object ServiceMaster {
  /**
    * Gets the props for creating a new ServiceMaster actor
    *
    * @param timeout timeout used for waiting for requests and futures
    * @return a Props for creating the actor with the given parameters
    */
  def props(implicit timeout: Timeout) = Props(new ServiceMaster())

  /**
    * Shard type used for know which shards are part of the masters
    */
  val shardType: String = "masters"
  /**
    * Given a message command, gets the unique entity id to distinguish in the shard
    */
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case cmd: ServiceMaster.Command => (cmd.serviceName, cmd)
  }
  /**
    * Given a message command, gets the shardId in which the entity shard is placed
    */
  val extractShardId: ShardRegion.ExtractShardId = {
    case cmd: ServiceMaster.Command => (cmd.serviceName.hashCode() % 100).toString
  }

  /**
    * A command represents a message for a ServiceMaster to perform some action
    */
  sealed trait Command {
    def serviceName: String
  }

  /**
    * Starts the Service
    *
    * @param serviceName name of the service
    * @param service     DockerService to run
    * @param instances   number of instances to start
    */
  case class StartService(serviceName: String, service: DockerService, instances: Int) extends Command

  /**
    * Scales the service
    *
    * @param serviceName name of the service
    * @param instances   new number of instances to run
    */
  case class ScaleService(serviceName: String, instances: Int) extends Command

  /**
    * Gets service info
    *
    * @param serviceName name of the service
    */
  case class Info(serviceName: String) extends Command

  /**
    * Stop the service
    *
    * @param serviceName name of the service
    */
  case class StopService(serviceName: String) extends Command

  /**
    * Indicates that a worker has accepted a task (an instance run)
    *
    * @param serviceName name of the service
    * @param worker      ServiceWorker that accepted the task
    */
  case class ServiceAccepted(serviceName: String, worker: ActorRef) extends Command

  /**
    * Indicates that the worker has failed running an instance (task)
    *
    * @param worker ServiceWorker that failed
    */
  case class WorkerFailed(worker: ActorRef)

  /**
    * An event is a change of state produced in the ServiceMaster that is persisted to the event journal
    */
  sealed trait Event

  /**
    * Event produced by the start of the service
    *
    * @param service   DockerService started
    * @param instances number of instances started
    */
  case class ServiceStarted(service: DockerService, instances: Int) extends Event

  /**
    * Event produced when scaling a service
    *
    * @param instances new number of instances running
    */
  case class ServiceScaled(instances: Int) extends Event

  /**
    * Event produced when stopping a service
    */
  case object ServiceStopped extends Event

  /**
    * A Response message contains info returned to the sender by the ServiceMaster
    */
  sealed trait Response

  /**
    * Service successfully created response
    *
    * @param name name of the service
    */
  case class ServiceCreated(name: String) extends Response

  /**
    * Service operation succeeded response
    *
    * @param name name of the service
    */
  case class ServiceOk(name: String) extends Response

  /**
    * Service already exists response
    *
    * @param name name of the service
    */
  case class ServiceExists(name: String) extends Response

  /**
    * Service creation failed response
    *
    * @param name name of the service
    */
  case class ServiceFailed(name: String) extends Response

  /**
    * Service list response
    *
    * @param services list of services running
    */
  case class ServiceList(services: List[String]) extends Response

  /**
    * Service Status response
    *
    * @param instances list of info from the instances of the service
    */
  case class ServiceStatus(instances: List[TaskInfo]) extends Response

  /**
    * A Snapshot contains the state of a ServiceMaster in a concrete time
    * to reduce recover time and number of messages.
    *
    * @param service   docker service started
    * @param instances instances running in the time of the snapshot
    */
  case class Snapshot(service: DockerService, instances: Int)

}

/**
  * A ServiceMaster actor is the main manager of a created service. It stores the state of the service
  * and send offers to the ServiceWorker associated with the master in each worker node for running
  * available instances. When an instance go down, the ServiceMaster restarts it in another node.
  * It is persistent and sharded, which means that is fault tolerant and can recover from a total
  * failure in the cluster comming back to the same state.
  *
  * @param timeout timeout used for waiting for requests and futures
  * @author Rober Veral (roberveral@gmail.com)
  * @see com.github.roberveral.dockerakka.cluster.master.ServiceMaster
  */
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
  var router: Option[ActorRef] = None

  // Initial timeout for detecting idle masters in sharding and removing them for memory saving
  context.setReceiveTimeout(60 seconds)

  // Sets stoppingStrategy, dead instances will be replaced in the cluster
  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy


  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // Unwatch workers
    workers.foreach(unwatch)
    // Resend Start message
    if (service.isDefined) self ! StartService(service.get.name, service.get, instances)
    super.preRestart(reason, message)
  }

  /**
    * Method for updating the Master state, based on the events that affects each master
    */
  private val updateState: Event => Unit = {
    case ServiceStarted(dservice, num) =>
      service = Some(dservice)
      instances = num
      // Create a pool of workers
      if (router.isEmpty) router = Some(createWorkerRouter(dservice))
      // Sends creation messages for each instance to the router
      (1 to instances) foreach (_ => context.system.scheduler.scheduleOnce(1000 millis, router.get, ServiceWorker.Service(service.get.name, self)))
      // Sets timeout for waiting for instances to join
      context.setReceiveTimeout(timeout.duration)
      // Finally, change to working state
      become(working(sender()))
    case ServiceScaled(num) =>
      instances = num
    case ServiceStopped =>
  }

  /**
    * Method used to update the state when a ServiceWorker failed running an instance
    *
    * @param worker ServiceWorker that failed
    */
  private def workerFailed(worker: ActorRef) = {
    log.warning(s"Worker $worker terminated unexpectly for service ${service.get.name}")
    // Remove worker from workers set
    workers = workers - worker
    // Sets timeout for waiting for instances to join
    context.setReceiveTimeout(timeout.duration)
    // Ask for a new worker to the router
    router.get ! ServiceWorker.Service(service.get.name, self)
  }

  /**
    * Method for receiving messages in the idle state.
    * In the idle state the ServiceMaster is waiting for a message to start the service.
    *
    * @return Receive
    */
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
      // If an actor is created for a non-running service, the actor is removed after a
      // timeout to save memory
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  /**
    * Method for receiving messages in the working state.
    * In the working state, the ServiceMaster is running a service and gets operation
    * messages over the running instances.
    *
    * @param creator the original sender of the StartService message
    * @return Receive
    */
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
        (workers.size to num) foreach (_ => router.get ! ServiceWorker.Service(service.get.name, self))
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
      // Save empty snapshot
      saveSnapshot(Snapshot(service.get, 0))
      log.info(s"Service ${service.get.name} stopped")

    case Info(_) =>
      // Get the info for all the workers
      import akka.pattern.{ask, pipe}
      log.info(s"master for service ${service.get.name} requesting status")
      // Gets info for all the childern services
      val childInfo: Set[Future[TaskInfo]] = workers.map(worker => (worker ? ServiceWorker.Info).mapTo[ServiceWorker.TaskInfo])
      pipe(Future.sequence(childInfo.toList).map(ServiceStatus)) to sender()

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
        (workers.size to instances) foreach (_ => router.get ! ServiceWorker.Service(service.get.name, self))
      } else
        context.setReceiveTimeout(Duration.Inf)

    case Terminated(worker) =>
      workerFailed(worker)

    case WorkerFailed(worker) =>
      workerFailed(worker)
  }

  // Persistence methods

  // In recovering, the state is rebuilt from the journal
  override def receiveRecover: Receive = {
    case ServiceStopped =>
      // An Stop event destroys the actor, similar to come back to idle state
      become(idle)
    case cmd: Event => updateState(cmd)
    case SnapshotOffer(_, _) => become(idle)
  }

  // Normal receive function
  override def receiveCommand: Receive = idle

  // Identifier to persistence
  override def persistenceId: String = self.path.name
}

/**
  * Trait that has the logic for creating a router pool of ServiceWorker actors,
  * one in each worker node of the cluster.
  */
trait CreateWorkerRouter {
  def context: ActorContext

  /**
    * Creates a router pool of ServiceWorker actors,
    * one in each worker node of the cluster. The router will balance
    * workload based in the metrics of each worker node.
    *
    * @param service DockerService that the ServiceWorker will run
    * @param timeout timeout used for waiting for requests and futures
    * @return the actor representing the router
    */
  def createWorkerRouter(service: DockerService)(implicit timeout: Timeout): ActorRef = {
    // The strategy for the router will be restart (to be sure that one worker is available in each
    // worker node in the cluster)
    val strategy = OneForOneStrategy() { case _ => Restart }
    // Creates a router that will balance the request using metrics of the node as placement strategy
    context.actorOf(
      ClusterRouterPool(AdaptiveLoadBalancingPool(MixMetricsSelector, supervisorStrategy = strategy),
        ClusterRouterPoolSettings(totalInstances = 1000, maxInstancesPerNode = 1,
          allowLocalRoutees = false, useRole = Some("worker"))).props(ServiceWorker.props(service)),
      name = s"router-${service.name}")
  }
}