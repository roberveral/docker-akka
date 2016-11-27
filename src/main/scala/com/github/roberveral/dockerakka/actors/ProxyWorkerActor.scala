package com.github.roberveral.dockerakka.actors

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorRef, Identify, Props, ReceiveTimeout, Terminated}

import scala.concurrent.duration._

/**
  * The ProxyWorkerActor communicates with a remote WorkerActor
  * and act as a proxy, monitoring its health.
  *
  * Created by roberveral on 27/11/16.
  */
object ProxyWorkerActor {
  def props(path: String) = Props(new ProxyWorkerActor(path))
}

/**
  * The ProxyWorkerActor communicates with a remote WorkerActor
  * and act as a proxy, monitoring its health.
  *
  * @param path the path of the remote actor.
  */
class ProxyWorkerActor(path: String) extends Actor with ActorLogging {
  // Set timeout for checking if the Worker is available
  context.setReceiveTimeout(3 seconds)
  // Send an Identify Request to the remote worker actor.
  sendIdentifyRequest()

  /**
    * Sends a Identify message to the remote actor, to check
    * if it is available
    */
  def sendIdentifyRequest(): Unit = {
    val selection = context.actorSelection(path)
    selection ! Identify(path)
  }

  // Receive starts in Identify status, waiting for the worker to be available
  override def receive: Receive = identify

  def identify: Receive = {
    case ActorIdentity(`path`, Some(actor)) =>
      // If the Worker is available, change the behaviour to active.
      context.setReceiveTimeout(Duration.Undefined)
      log.info("Worker {} identified. Switching to active.", path)
      context.become(active(actor))
      // Monitor the status of the remote worker.
      context.watch(actor)

    case ActorIdentity(`path`, None) =>
      log.error(s"Remote worker {} is not available.", path)

    case ReceiveTimeout =>
      sendIdentifyRequest()

    case msg: Any =>
      log.error(s"Ignoring message $msg, remote worker is not ready yet.")
  }

  def active(actor: ActorRef): Receive = {
    case Terminated(actorRef) =>
      // In case the worker deads, return to identify state and wait for it to recover.
      log.info("Worker {} terminated. Switching to identify state.", path)
      context.become(identify)
      context.setReceiveTimeout(3 seconds)
      sendIdentifyRequest()
    // In active mode, the Proxy forwards all the messages.
    case msg: Any => actor forward msg
  }

}
