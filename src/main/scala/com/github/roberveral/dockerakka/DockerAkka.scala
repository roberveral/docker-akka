package com.github.roberveral.dockerakka

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.util.Timeout
import com.github.roberveral.dockerakka.http.{ApiStartup, RestAPI}

import scala.concurrent.duration._

/**
  * Main class for the DockerAkka application
  *
  * @author Rober Veral (roberveral@gmail.com)
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
