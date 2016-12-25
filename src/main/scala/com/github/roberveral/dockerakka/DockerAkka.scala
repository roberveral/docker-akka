package com.github.roberveral.dockerakka

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.util.Timeout
import com.github.roberveral.dockerakka.cluster.ConsulBootstrapping
import com.github.roberveral.dockerakka.http.{ApiStartup, RestAPI}

import scala.concurrent.duration._

/**
  * Main class for the DockerAkka application
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
object DockerAkka extends App with ApiStartup with ConsulBootstrapping {
  // Creates the Actor System
  implicit val system = ActorSystem("dockerakka")
  // Gets an implicit configuration reference
  implicit val config = system.settings.config
  implicit val ec = system.dispatcher
  implicit val requestTimeout: Timeout = Timeout(5 seconds)
  val cluster = Cluster(system)

  // Registers the service with Consul
  registerService
  system.log.info(s"Starting node with roles: ${Cluster(system).selfRoles}")

  // Retries cluster joining until success
  val scheduler = system.scheduler.schedule(2 seconds, 5 seconds, new Runnable {
    override def run(): Unit = {
      // Gets the node address
      val selfAddress = cluster.selfAddress
      system.log.info(s"Cluster bootstrap, self address: $selfAddress")
      // Get the seeds addresses
      val serviceAddresses = getSeedAddresses
      system.log.info(s"Cluster bootstrap, service addresses: $serviceAddresses")

      // http://doc.akka.io/docs/akka/2.4.4/scala/cluster-usage.html
      //
      // When using joinSeedNodes you should not include the node itself except for the node
      // that is supposed to be the first seed node, and that should be placed first
      // in parameter to joinSeedNodes.
      val seedNodes = serviceAddresses filter { address =>
        address != selfAddress || address == serviceAddresses.head
      }
      system.log.info(s"Cluster bootstrap, found service seeds: $seedNodes")
      // Joins the cluster using that seed nodes
      cluster.joinSeedNodes(seedNodes)
    }
  })

  // Wait to have at least one worker and then become UP with the master actors deployed
  cluster.registerOnMemberUp {
    system.log.info("Cluster is ready!")
    scheduler.cancel()
    // Check if the node is launched in "master" role
    if (system.settings.config.getStringList("akka.cluster.roles").contains("master")) {
      val api = new RestAPI()
      startup(api.routes)
    }
  }

  // When the system is terminated, remove the service from Consul
  system.registerOnTermination(deregisterService)
}
