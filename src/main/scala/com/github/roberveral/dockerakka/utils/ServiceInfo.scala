package com.github.roberveral.dockerakka.utils

/**
  * Class that contains Service info (details of a running container)
  * @param service service from which the info comes
  * @param info details of the running container
  */
case class ServiceInfo(service: DockerService, info: String)
