package com.github.roberveral.dockerakka.cluster.master

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Kill, PoisonPill, Props, ReceiveTimeout, SupervisorStrategy, Terminated}
import akka.cluster.metrics.{AdaptiveLoadBalancingPool, MixMetricsSelector}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
import com.github.roberveral.dockerakka.utils.DockerService

import scala.collection.immutable.Iterable
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by roberveral on 6/12/16.
  */
object ServiceMaster {
  def props(service: DockerService)(implicit timeout: Timeout) = Props(new ServiceMaster(service))

  // Message API
  // Start the Service
  case class StartService(instances: Int)
  // Scale the service
  case class ScaleService(instances: Int)
  // Get service info
  case object Info
  // Stop the service
  case object StopService
  // Worker accepted task message
  case class ServiceAccepted(worker: ActorRef)
}

class ServiceMaster(service: DockerService)(implicit timeout: Timeout) extends Actor with ActorLogging with CreateWorkerRouter {
  import ServiceMaster._
  import context._

  // Keep track of the instances of the service
  var instances: Int = 0
  // Keep track of the running instances
  var running: Int = 0
  // Keep track of registered workers
  var workers: Set[ActorRef] = Set[ActorRef]()

  // Create the router to send job to the workers
  val router: ActorRef = createWorkerRouter

  // Sets stoppingStrategy, a dead instances will be replaced in the cluster
  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy


  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.info("master prerestart")
    // Resend Start message
    self ! StartService(instances)
    super.preRestart(reason, message)
  }

  override def receive: Receive = idle

  def idle: Receive = {
    // In Idle state, the Master is waiting for an incoming Job
    case StartService(num) =>
      instances = num
      // Sends creation messages for each instance to the router
      (1 to instances) foreach(_ =>  context.system.scheduler.scheduleOnce(1000 millis, router, ServiceWorker.Service(service.name, self)))
      // Sets timeout for waiting for instances to join
      context.setReceiveTimeout(timeout.duration)
      // Reply sender that the Service is created
      sender() ! ServiceScheduler.ServiceCreated(service.name)
      // Finally, change to working state
      become(working(sender()))
      log.info(s"Starting service ${service.name}. Switching to WORKING")
  }

  def working(creator: ActorRef): Receive = {
    case ServiceAccepted(worker) =>
      if (running == instances)
        worker ! ServiceWorker.NoTasksAvailable
      else {
        // Add the worker to the worker list
        workers = workers + worker
        // Increment running instances
        running = running + 1
        // Monitor worker for termination
        watch(worker)
        // Send worker task to perform
        worker ! ServiceWorker.ServiceTask(service)
        log.info(s"Worker accepted task for ${service.name}")
      }

    case ScaleService(num) =>
      if (num > instances) {
        // Set new number of instances if is greater (scale-out)
        instances = num
        // Send new tasks to router
        (running to instances) foreach (_ => router ! ServiceWorker.Service(service.name, self))
        // Sets timeout for waiting for instances to join
        context.setReceiveTimeout(timeout.duration)
      } else if (num < instances) {
        // See how many instances to kill (scale-down)
        val dif = instances - num
        val kill = workers.take(dif)
        // Kill the instances and update the state
        kill.foreach(unwatch)
        kill.foreach(stop)
        workers = workers -- kill
        instances = num
      }
      sender() ! ServiceScheduler.ServiceOk(service.name)
      log.info(s"Service ${service.name} scaled to $instances instances")

    case StopService =>
      // Unwatch and end all the workers
      workers foreach unwatch
      // End all the workers
      workers foreach (_ ! ServiceWorker.Cancel)
      // Send an OK response
      sender() ! ServiceScheduler.ServiceOk(service.name)
      // End the master
      self ! PoisonPill
      log.info(s"Service ${service.name} stopped")

    case Info =>
      // Get the info for all the workers
      import akka.pattern.ask
      import akka.pattern.pipe
      // Gets info for all the childern services
      val childInfo: Iterable[Future[ServiceWorker.TaskInfo]] = workers.map(_.ask(ServiceWorker.Info).mapTo[ServiceWorker.TaskInfo])
      // Pipes the resulting list to the sender
      pipe(Future.sequence(childInfo)) to sender()

    case ReceiveTimeout =>
      if (workers.isEmpty) {
        log.error(s"No workers accepted tasks on time for service ${service.name}. Ending master")
        // Sends ServiceFailed to the creator
        creator ! ServiceScheduler.ServiceFailed(service.name)
        // Ends itself due to inability to get resources
        self ! Kill
      } else if (running < instances) {
        log.warning(s"Not enough resources for all the tasks for service ${service.name}. Resending work messages to workers")
        // Send another time remaining instances notification
        (running to instances) foreach (_ => router ! ServiceWorker.Service(service.name, self))
      } else
        context.setReceiveTimeout(Duration.Inf)

    case Terminated(worker) =>
      log.warning(s"Worker $worker terminated unexpectly for service ${service.name}")
      // Remove worker from workers set
      workers = workers - worker
      // Decrease number of running instances
      running = running - 1
      // Sets timeout for waiting for instances to join
      context.setReceiveTimeout(timeout.duration)
      // Ask for a new worker to the router
      router ! ServiceWorker.Service(service.name, self)

  }
}

trait CreateWorkerRouter {
  def context: ActorContext
  def createWorkerRouter(implicit timeout: Timeout): ActorRef = {
    // Creates a router that will balance the request using metrics of the node as placement strategy
    context.actorOf(
      ClusterRouterPool(AdaptiveLoadBalancingPool(MixMetricsSelector), ClusterRouterPoolSettings(
        totalInstances = 1000, maxInstancesPerNode = 1,
        allowLocalRoutees = false, useRole = Some("worker"))).props(ServiceWorker.props),
      name = "worker-router")
  }
}