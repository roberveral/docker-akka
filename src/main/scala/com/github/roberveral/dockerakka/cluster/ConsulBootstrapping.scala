package com.github.roberveral.dockerakka.cluster

import akka.actor.{ActorSystem, Address}
import com.github.roberveral.dockerakka.utils.ConsulAgent
import com.google.common.net.HostAndPort
import com.orbitz.consul.Consul
import com.orbitz.consul.option.{ConsistencyMode, ImmutableQueryOptions}
import com.typesafe.config.Config

import scala.collection.JavaConversions._

/**
  * Implements the Bootstrapping methods using Consul as service discovery util.
  * It's based on http://sap1ens.com/blog/2016/11/12/bootstrapping-akka-cluster-with-consul/
  * with a few tweaks to adapt it to the dcker-akka orchestrator.
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
trait ConsulBootstrapping extends ConsulAgent {
  /**
    * Registers the akka service in Consul
    * @param config configuration to use to extract values
    */
  def registerService(implicit config: Config): Unit = {
    // Gets a consul agent to interact with the service
    val consul: Consul = getConsulAgent
    // Gets host and port from configuration
    val akkaPort: Int = config.getInt("akka.remote.netty.tcp.port")
    val akkaHost: String = config.getString("akka.remote.netty.tcp.hostname")
    val akkaRole: String = config.getStringList("akka.cluster.roles").get(0)
    // Register the service in Consul
    consul.agentClient().register(akkaPort,
      HostAndPort.fromParts(akkaHost, akkaPort),
      5L,
      s"docker-akka-$akkaRole",
      s"$akkaRole.$akkaHost.$akkaPort",
      "docker-akka", "orchestrator")
  }

  /**
    * Gets the healthy seed addresses from Consul. The bootstrapping is performed against the
    * masters, so only masters are considered.
    * @param config configuration to use to extract values
    * @param system ActorSystem that is going to be joined
    * @return list of healthy master addresses
    */
  def getSeedAddresses(implicit config: Config, system: ActorSystem): List[Address] = {
    // Gets a consul agent to interact with the service
    val consul: Consul = getConsulAgent
    // Set query options for Consul
    val queryOpts = ImmutableQueryOptions
      .builder()
      .consistencyMode(ConsistencyMode.CONSISTENT)
      .build()
    // Get registered master nodes (only master nodes are used for bootstrapping)
    val serviceNodes = consul.healthClient().getHealthyServiceInstances("docker-akka-master", queryOpts)

    // Create a list of remote addresses with the healthy master nodes
    serviceNodes.getResponse.toList map { node =>
      Address("akka.tcp", system.name, node.getNode.getAddress, node.getService.getPort)
    }
  }

  /**
    * Removes the Akka service from the Consul agent
    * @param config configuration to use to extract values
    */
  def deregisterService(implicit config: Config): Unit = {
    // Gets a consul agent to interact with the service
    val consul: Consul = getConsulAgent
    // Gets host and port from configuration
    val akkaPort: Int = config.getInt("akka.remote.netty.tcp.port")
    val akkaHost: String = config.getString("akka.remote.netty.tcp.hostname")
    val akkaRole: String = config.getStringList("akka.cluster.roles").get(0)
    // Deregister the service from consul
    consul.agentClient().deregister(s"$akkaRole.$akkaHost.$akkaPort")
  }
}
