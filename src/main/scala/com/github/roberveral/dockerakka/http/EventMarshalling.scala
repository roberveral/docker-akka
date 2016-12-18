package com.github.roberveral.dockerakka.http

import com.github.roberveral.dockerakka.cluster.master.ServiceMaster
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
import com.github.roberveral.dockerakka.utils.DockerService
import spray.json._

/**
  * Definition of a ServiceDescription for the input request
  *
  * @param image     name of the image for the Docker service
  * @param ports     list of ports to bind in the Docker service
  * @param instances number of instances to create of the service
  * @author Rober Veral (roberveral@gmail.com)
  */
case class ServiceDescription(image: String, ports: List[Int], instances: Int) {
  require(instances > 0)
  require(image.nonEmpty)
}

/**
  * Definition of service instances for the input request
  *
  * @param instances new number of instances
  * @author Rober Veral (roberveral@gmail.com)
  */
case class ServiceInstances(instances: Int) {
  require(instances > 0)
}

/**
  * Trait with the implicit conversions from case classes to JSON
  * for marshalling and unmarshallig of requests.
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
trait EventMarshalling extends DefaultJsonProtocol {
  implicit val serviceInstances = jsonFormat1(ServiceInstances)
  implicit val serviceDesc = jsonFormat3(ServiceDescription)
  implicit val serviceFormat = jsonFormat3(DockerService)
  implicit val serviceListFormat = jsonFormat1(ServiceMaster.ServiceList)
  implicit val serviceResponse = jsonFormat1(ServiceWorker.TaskInfo)
}
