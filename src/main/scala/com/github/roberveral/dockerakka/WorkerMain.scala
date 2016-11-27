package com.github.roberveral.dockerakka

import akka.actor.ActorSystem
import akka.util.Timeout
import com.github.roberveral.dockerakka.actors.WorkerActor
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

/**
  * Main that executes docker-akka in Worker mode, for
  * launching services.
  *
  * Created by roberveral on 27/11/16.
  */
object WorkerMain extends App {
  // Gets the configuration from file
  val config = ConfigFactory.load("worker")
  // Creates the ActorSystem
  implicit val system = ActorSystem("dockerakka", config)
  implicit val requestTimeout: Timeout = Timeout(30 seconds)

  // Creates Worker actor
  system.actorOf(WorkerActor.props, WorkerActor.name)
}
