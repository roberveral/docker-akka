package com.github.roberveral.dockerakka.http

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.master.{ServiceScheduler, ServiceMaster}
import com.github.roberveral.dockerakka.utils.DockerService
import akka.pattern.ask


/**
  * Created by roberveral on 6/12/16.
  */
class RestAPI(implicit system: ActorSystem, timeout: Timeout) extends EventMarshalling {

  import ServiceMaster._
  import ServiceScheduler._
  import StatusCodes._

  def createScheduler(implicit timeout: Timeout): ActorRef = system.actorOf(ServiceScheduler.props, "scheduler")

  // Creates the Scheduler instance
  val scheduler: ActorRef = createScheduler

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
            onSuccess(scheduler ? StartService(service.name, DockerService(service, sd.image, sd.ports), sd.instances)) {
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
            onSuccess(scheduler ? StopService(service)) {
              case ServiceOk(_) => complete(OK)
              case _ => complete(NotFound)
            }
          } ~
          put {
            // PUT /services/:service
            // Scales a service
            entity(as[ServiceInstances]) { sn =>
              onSuccess(scheduler ? ScaleService(service, sn.instances)) {
                case ServiceOk(_) => complete(OK)
                case _ => complete(NotFound)
              }
            }
          } ~
          get {
            // GET /services/:service
            // Gets service information
            onSuccess(scheduler ? Info(service)) {
              case ServiceStatus(e) => complete(OK, e)
              case _ => complete(NotFound)
            }
          }
      }

    }


}
