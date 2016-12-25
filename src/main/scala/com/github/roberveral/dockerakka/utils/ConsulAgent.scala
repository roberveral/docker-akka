package com.github.roberveral.dockerakka.utils

import com.orbitz.consul.Consul
import com.typesafe.config.Config

/**
  * Implements a interface with a Consul agent to interact with
  * Consul using the parameters provided in the configuration for
  * the connection.
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
trait ConsulAgent {
  /**
    * Gets a Consul Agent reference for the given worker
    *
    * @param config configuration from which get the Consul connection info.
    * @return consul object referencing the agent
    */
  def getConsulAgent(implicit config: Config): Consul = {
    val hostname = config.getString("consul.host")
    val port = config.getString("consul.port")
    Consul.builder().withUrl(s"http://$hostname:$port").build()
  }
}
