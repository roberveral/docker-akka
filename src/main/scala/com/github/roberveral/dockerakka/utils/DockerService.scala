package com.github.roberveral.dockerakka.utils

/**
  * Defines a Docker based service.
  *
  * @param name Name of the service.
  * @param image Docker image to use for the service.
  * @param ports Ports exposed to the host
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
case class DockerService(name: String, image: String, ports: List[Int])
