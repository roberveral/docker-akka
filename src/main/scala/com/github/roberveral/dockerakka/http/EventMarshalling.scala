package com.github.roberveral.dockerakka.http

import com.github.roberveral.dockerakka.cluster.master.ServiceScheduler
import com.github.roberveral.dockerakka.cluster.master.ServiceScheduler.ServiceList
import com.github.roberveral.dockerakka.cluster.worker.ServiceWorker
import com.github.roberveral.dockerakka.utils.DockerService
import spray.json._


case class ServiceDescription(image: String, ports: Map[String, String], instances: Int) {
  require(instances > 0)
  require(image.nonEmpty)
}

case class ServiceInstances(instances: Int) {
  require(instances > 0)
}

/**
  * Created by roberveral on 6/12/16.
  */
trait EventMarshalling extends DefaultJsonProtocol {
  implicit val serviceInstances = jsonFormat1(ServiceInstances)
  implicit val serviceDesc = jsonFormat3(ServiceDescription)
  implicit val serviceFormat = jsonFormat3(DockerService)
  implicit val serviceCreationFormat = jsonFormat2(ServiceScheduler.Create)
  implicit val serviceScaleFormat = jsonFormat2(ServiceScheduler.Scale)
  implicit val serviceDestroyFormat = jsonFormat1(ServiceScheduler.Destroy)
  implicit val serviceListFormat = jsonFormat1(ServiceScheduler.ServiceList)
  implicit val serviceResponse = jsonFormat1(ServiceWorker.TaskInfo)
}
