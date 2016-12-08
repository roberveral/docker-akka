package com.github.roberveral.dockerakka

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.master.ServiceScheduler
import com.github.roberveral.dockerakka.http.{ApiStartup, RestAPI}
import com.github.roberveral.dockerakka.utils.DockerService

import scala.concurrent.duration._

/**
  * Created by roberveral on 6/12/16.
  */
object DockerAkka extends App with ApiStartup {
  // Creates the Actor System
  implicit val system = ActorSystem("dockerakka")
  implicit val requestTimeout: Timeout = Timeout(5 seconds)

  system.log.info(s"Starting node with roles: ${Cluster(system).selfRoles}")

  // Check if the node is launched in "master" role
  if(system.settings.config.getStringList("akka.cluster.roles").contains("master")) {
    // Wait to have at least one worker and then become UP with the master actors deployed
    Cluster(system).registerOnMemberUp {
      val api = new RestAPI()
      startup(api.routes)
    }
  }

}
