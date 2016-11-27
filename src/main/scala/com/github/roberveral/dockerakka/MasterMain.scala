package com.github.roberveral.dockerakka

import akka.actor.ActorSystem
import akka.util.Timeout
import com.github.roberveral.dockerakka.actors.{MasterActor, ProxyWorkerActor}
import com.github.roberveral.dockerakka.utils.DockerService
import com.github.roberveral.dockerakka.utils.placement.RoundRobinStrategy
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

/**
  * Main to launch docker-akka in master mode, as a
  * dispatcher for creating services based in Docker containers.
  *
  * Created by roberveral on 27/11/16.
  */
object MasterMain extends App {
  // Loads the configuration from file
  val config = ConfigFactory.load("master")

  // Creates the Actor System
  implicit val system = ActorSystem("dockerakka", config)

  implicit val requestTimeout: Timeout = Timeout(30 seconds)

  // Create ProxyActor for each worker node
  val workers = config.getString("workers.list").split(",").map(path => system.actorOf(ProxyWorkerActor.props(path)))

  // Create Master actor
  val master = system.actorOf(MasterActor.props(workers, new RoundRobinStrategy(0)))


  // For testing (until it has HTTP interface) launches a service
  scala.io.StdIn.readLine()
  // Launch Nginx
  master ! MasterActor.Create(DockerService("nginx", "nginx:latest", Map((80, 80))))
}
