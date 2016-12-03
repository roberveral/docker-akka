package com.github.roberveral.dockerakka.actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import com.github.roberveral.dockerakka.utils.{DockerService, ServiceInfo}
import com.github.roberveral.dockerakka.utils.placement.PlacementStrategy

import scala.concurrent.Future

/**
  * A MasterActor orchestrates and manages the Docker services launched,
  * selecting the remote WorkerActor in which a service is placed, based in
  * the ProxyWorkerActor list given.
  *
  * Created by roberveral on 27/11/16.
  */
object MasterActor {
  def props(workers: Seq[ActorRef], placementStrategy: PlacementStrategy)(implicit timeout: Timeout) =
    Props(new MasterActor(workers, placementStrategy))

  def name = "master"

  // Message API definition
  // Creates and launches a new DockerService in the system.
  case class Create(service: DockerService)

  // Destroys a running DockerService
  case class Destroy(name: String)

  // Gets the info of a running service
  case class Info(name: String)

  // Mesage used to get info of all running services
  case object AllInfo

}

/**
  * A MasterActor orchestrates and manages the Docker services launched,
  * selecting the remote WorkerActor in which a service is placed, based in
  * the ProxyWorkerActor list given.
  *
  * @param workers           list of ProxyWorkerActor ActorRef of the remote workers.
  * @param placementStrategy strategy to use for selecting a worker in which to launch a service.
  * @param timeout           asynchronous requests timeouts.
  */
class MasterActor(workers: Seq[ActorRef], placementStrategy: PlacementStrategy)(implicit timeout: Timeout) extends Actor
  with ActorLogging {

  import MasterActor._
  import context._

  // Monitor all the workers to check for termination
  workers.foreach(watch)

  def receive(placement: PlacementStrategy): Receive = {
    case Create(service) => {
      // Get the worker in which to place the service
      val (worker, newStrategy) = placement.getPlace(workers)
      // Check if it can be allocated
      worker.fold(log.error("{} could not be allocated.", service))((actor) => {
        // Tell Worker to start the service
        actor ! WorkerActor.Launch(service)
        // Update the strategy used
        become(receive(newStrategy))
        log.info("{} created in worker {}", service, actor.path)
      })
    }
    case Info(name) => workers.foreach(_ forward WorkerActor.Info(name))
    case AllInfo =>
      import akka.pattern.ask
      import akka.pattern.pipe
      // Collects the info from each worker
      val workerInfo: Seq[Future[Iterable[ServiceInfo]]] = workers.map(_.ask(WorkerActor.AllInfo).mapTo[Iterable[ServiceInfo]])
      // Pipes the info to the sender
      pipe(Future.sequence(workerInfo)) to sender()
    case Destroy(name) => workers.foreach(_ ! WorkerActor.Stop(name))
  }

  override def receive: Receive = receive(placementStrategy)
}
