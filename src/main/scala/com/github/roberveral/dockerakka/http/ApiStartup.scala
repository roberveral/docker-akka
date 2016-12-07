package com.github.roberveral.dockerakka.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.Future

/**
  * Created by roberveral on 6/12/16.
  */
trait ApiStartup {
  def startup(api: Route)(implicit system: ActorSystem, timeout: Timeout): Unit = {
    // Gets the host and a port from the configuration
    val host = system.settings.config.getString("http.host")
    val port = system.settings.config.getInt("http.port")
    // Starts HTTP server
    startHttpServer(api, host, port)
  }

  def startHttpServer(api: Route, host: String, port: Int)
                     (implicit system: ActorSystem, timeout: Timeout): Unit = {
    // Gets the implicit execution context
    implicit val ec = system.dispatcher
    implicit val materializer = ActorMaterializer()
    // Binds HTTP API to the host interface
    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(api, host, port)

    // Checks the final result
    val log = system.log
    bindingFuture.map { serverBinding =>
      log.info(s"RestApi bound to ${serverBinding.localAddress} ")
    }.onFailure {
      case ex: Exception =>
        log.error(ex, "Failed to bind to {}:{}!", host, port)
        system.terminate()
    }
  }

}
