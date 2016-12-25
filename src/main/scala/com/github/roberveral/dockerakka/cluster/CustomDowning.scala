package com.github.roberveral.dockerakka.cluster

import akka.ConfigurationException
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.ClusterEvent.{ClusterDomainEvent, ReachableMember, UnreachableMember}
import akka.cluster.{Cluster, DowningProvider, Member}
import com.github.roberveral.dockerakka.cluster.CustomDowningActor.UnreachableTimeout

import scala.concurrent.duration.FiniteDuration

/**
  * A companion object for the CustomDowningActor, that performs the auto downing
  * using majority as criteria.
  * (http://stackoverflow.com/questions/30575174/how-to-configure-downing-in-akka-cluster-when-a-singleton-is-present)
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
object CustomDowningActor {
  /**
    * Returns the Props for a CustomDowningActor
    *
    * @param autoDownTimeout timeout for removing an unreachable node.
    * @return the props for creating the actor
    */
  def props(autoDownTimeout: FiniteDuration): Props = Props(new CustomDowningActor(autoDownTimeout))

  /**
    * Message sent to remove a member from the cluster when a timeout expires
    *
    * @param member member to remove
    */
  case class UnreachableTimeout(member: Member)

}

/**
  * The CustomDowning strategy uses the criteria of the majority to autodown nodes avoiding network partition problems.
  * It checks if a node belongs to the majority of the cluster before letting it down an unreachable node.
  * (http://stackoverflow.com/questions/30575174/how-to-configure-downing-in-akka-cluster-when-a-singleton-is-present)
  *
  * @author Rober Veral (roberveral@gmail.com)
  */
class CustomDowning(system: ActorSystem) extends DowningProvider {

  private def clusterSettings = Cluster(system).settings

  override def downRemovalMargin: FiniteDuration = clusterSettings.AutoDownUnreachableAfter.asInstanceOf[FiniteDuration]

  override def downingActorProps: Option[Props] =
    clusterSettings.AutoDownUnreachableAfter match {
      case d: FiniteDuration ⇒ Some(CustomDowningActor.props(d))
      case _ =>
        throw new ConfigurationException("CustomDowning downing provider selected but 'akka.cluster.auto-down-unreachable-after' not set")
    }
}

/**
  * The CustomDowning strategy uses the criteria of the majority to autodown nodes avoiding network partition problems.
  * It checks if a node belongs to the majority of the cluster before letting it down an unreachable node.
  * (http://stackoverflow.com/questions/30575174/how-to-configure-downing-in-akka-cluster-when-a-singleton-is-present)
  *
  * @param autoDownTimeout timeout for removing an unreachable node
  * @author Rober Veral (roberveral@gmail.com)
  */
class CustomDowningActor(autoDownTimeout: FiniteDuration) extends Actor with ActorLogging {
  val cluster = Cluster(context.system)
  var unreachableMember: Set[Member] = Set()

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[ClusterDomainEvent])
    super.preStart()
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = {
    case UnreachableMember(member) =>
      log.info(s"[CustomDowning] $member detected unreachable.")
      val state = cluster.state
      // See if the actor is in the majority of the members
      if (isMajority(state.members.size, state.unreachable.size)) {
        // Add the member to the list and schedule a down timeout if it remains unreachable
        unreachableMember = unreachableMember + member
        implicit val ec = context.system.dispatcher
        context.system.scheduler.scheduleOnce(autoDownTimeout, self, UnreachableTimeout(member))
      }

    case ReachableMember(member) =>
      log.info(s"[CustomDowning] $member detected reachable again.")
      // Remove member from the list
      unreachableMember = unreachableMember - member

    case UnreachableTimeout(member) =>
      // Check if the member is still unreachable
      if (unreachableMember.contains(member)) cluster.down(member.address)

    case _: ClusterDomainEvent => // ignore
  }

  /**
    * find out majority number of the group
    *
    * @param n number to calculate majority
    * @return majority
    */
  private def majority(n: Int): Int = (n + 1) / 2 + (n + 1) % 2

  /**
    * Check if alive is majority or dead is
    *
    * @param total total number of elements
    * @param dead  dead number of elements
    * @return true if alive is majority over dead
    */
  private def isMajority(total: Int, dead: Int): Boolean = {
    require(total > 0)
    require(dead >= 0)
    (total - dead) >= majority(total)
  }
}