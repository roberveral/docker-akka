package com.github.roberveral.dockerakka.http

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.master.ServiceScheduler
import com.github.roberveral.dockerakka.utils.DockerService
import akka.pattern.ask
import com.github.roberveral.dockerakka.cluster.master.ServiceScheduler._
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker.TaskInfo

/**
  * Created by roberveral on 6/12/16.
  */
class RestAPI(implicit system: ActorSystem, timeout: Timeout) extends EventMarshalling {

  import ServiceScheduler._
  import StatusCodes._

  def createScheduler(implicit timeout: Timeout) = system.actorOf(ServiceScheduler.props, "scheduler")

  // Creates the Scheduler instance
  lazy val scheduler = createScheduler

  def routes: Route = servicesRoute ~ serviceRoute

  def servicesRoute: Route =
    pathPrefix("services") {
      pathEndOrSingleSlash {
        get {
          onSuccess(scheduler ? ServiceScheduler.Status) {
            case ServiceList(ls) => complete(OK, ls)
            case _ => complete(InternalServerError)
          }
        }
      }
    }

  def serviceRoute: Route =
    pathPrefix("services" / Segment) { service =>
      pathEndOrSingleSlash {
        post {
          // POST /services/:service
          // Creates a new service
          entity(as[ServiceDescription]) { sd =>
            onSuccess(scheduler ? Create(DockerService(service, sd.image, sd.ports.map { case (k, v) => (k.toInt, v.toInt) }), sd.instances)) {
              case ServiceCreated(_) => complete(Created)
              case ServiceExists(_) =>
                complete(BadRequest, s"$name service exists already.")
              case _ => complete(InternalServerError)
            }
          }
        } ~
          delete {
            // DELETE /services/:service
            // Destroys a service
            onSuccess(scheduler ? Destroy(service)) {
              case ServiceOk(_) => complete(OK)
              case _ => complete(NotFound)
            }
          } ~
          put {
            // PUT /services/:service
            // Scales a service
            entity(as[ServiceInstances]) { sn =>
              onSuccess(scheduler ? Scale(service, sn.instances)) {
                case ServiceOk(_) => complete(OK)
                case _ => complete(NotFound)
              }
            }
          } ~
          get {
            // GET /services/:service
            // Gets service information
            onSuccess(scheduler ? Status(service)) {
              case ServiceStatus(e) => complete(OK, e)
              case _ => complete(NotFound)
            }
          }
      }

    }


}
