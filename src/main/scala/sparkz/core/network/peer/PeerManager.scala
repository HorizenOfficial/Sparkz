package sparkz.core.network.peer

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import sparkz.core.app.SparkzContext
import sparkz.core.network._
import sparkz.core.settings.SparkzSettings
import sparkz.core.utils.NetworkUtils
import scorex.util.ScorexLogging

import java.net.{InetAddress, InetSocketAddress}
import scala.util.Random

/**
  * Peer manager takes care of peers connected and in process, and also chooses a random peer to connect
  * Must be singleton
  */
class PeerManager(settings: SparkzSettings, sparkzContext: SparkzContext) extends Actor with ScorexLogging {

  import PeerManager.ReceivableMessages._

  private val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext.timeProvider)
  private val knownPeersSet: Set[InetSocketAddress] = settings.network.knownPeers.toSet

  if (peerDatabase.isEmpty) {
    // fill database with peers from config file if empty
    knownPeersSet.foreach { address =>
      if (!isSelf(address)) {
        peerDatabase.addOrUpdateKnownPeer(PeerInfo.fromAddress(address))
      }
    }
  }

  override def receive: Receive = testLog orElse peersManagement orElse apiInterface orElse {
    case a: Any =>
      log.error(s"Wrong input for peer manager: $a")
  }

  def testLog: Receive =  new Receive {
    def isDefinedAt(x: Any) = {
      sparkz.core.debug.MessageCounters.log("PeerManager", x)
      false
    }
    def apply(x: Any) = throw new UnsupportedOperationException  
  }


  private def peersManagement: Receive = {

    case ConfirmConnection(connectionId, handlerRef) =>
      log.info(s"Connection confirmation request: $connectionId")
      if (peerDatabase.isBlacklisted(connectionId.remoteAddress)) sender() ! ConnectionDenied(connectionId, handlerRef)
      else sender() ! ConnectionConfirmed(connectionId, handlerRef)

    case AddOrUpdatePeer(peerInfo) =>
      // We have connected to a peer and got his peerInfo from him
      if (!isSelf(peerInfo.peerSpec)) peerDatabase.addOrUpdateKnownPeer(peerInfo)

    case Penalize(peer, penaltyType) =>
      log.info(s"$peer penalized, penalty: $penaltyType")
      if (peerDatabase.penalize(peer, penaltyType)) {
        log.info(s"$peer blacklisted")
        peerDatabase.addToBlacklist(peer, penaltyType)
        sender() ! Blacklisted(peer)
      }

    case AddPeersIfEmpty(peersSpec) =>
      // We have received peers data from other peers. It might be modified and should not affect existing data if any
      val filteredPeers = peersSpec
        .collect {
          case peerSpec if peerSpec.address.forall(a => peerDatabase.get(a).isEmpty) && !isSelf(peerSpec) =>
            val peerInfo: PeerInfo = PeerInfo(peerSpec, 0L, None)
            log.info(s"New discovered peer: $peerInfo")
            peerInfo
        }
      peerDatabase.addOrUpdateKnownPeers(filteredPeers)

    case RemovePeer(address) =>
      if (!knownPeersSet(address)) {
        peerDatabase.remove(address)
        log.info(s"$address removed from peers database")
      }

    case get: GetPeers[_] =>
      sender() ! get.choose(peerDatabase.knownPeers, peerDatabase.blacklistedPeers, sparkzContext)
  }

  private def apiInterface: Receive = {

    case GetAllPeers =>
      log.trace(s"Get all peers: ${peerDatabase.knownPeers}")
      sender() ! peerDatabase.knownPeers

    case GetBlacklistedPeers =>
      sender() ! peerDatabase.blacklistedPeers
  }

  /**
    * Given a peer's address, returns `true` if the peer is the same is this node.
    */
  private def isSelf(peerAddress: InetSocketAddress): Boolean = {
    NetworkUtils.isSelf(peerAddress, settings.network.bindAddress, sparkzContext.externalNodeAddress)
  }

  private def isSelf(peerSpec: PeerSpec): Boolean = {
    peerSpec.declaredAddress.exists(isSelf) || peerSpec.localAddressOpt.exists(isSelf)
  }

}

object PeerManager {

  object ReceivableMessages {

    case class ConfirmConnection(connectionId: ConnectionId, handlerRef: ActorRef)

    case class ConnectionConfirmed(connectionId: ConnectionId, handlerRef: ActorRef)

    case class ConnectionDenied(connectionId: ConnectionId, handlerRef: ActorRef)

    case class Penalize(remote: InetSocketAddress, penaltyType: PenaltyType)

    case class Blacklisted(remote: InetSocketAddress)

    // peerListOperations messages
    case class AddOrUpdatePeer(data: PeerInfo)

    case class AddPeersIfEmpty(data: Seq[PeerSpec])

    case class RemovePeer(address: InetSocketAddress)

    /**
      * Message to get peers from known peers map filtered by `choose` function
      */
    trait GetPeers[T] {
      def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                 blacklistedPeers: Seq[InetAddress],
                 sparkzContext: SparkzContext): T
    }

    /**
      * Choose at most `howMany` random peers, which were connected to our peer and weren't blacklisted.
      *
      * Used in peer propagation: peers chosen are recommended to a peer asking our node about more peers.
      */
    case class SeenPeers(howMany: Int) extends GetPeers[Seq[PeerInfo]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Seq[PeerInfo] = {
        val recentlySeenNonBlacklisted = knownPeers.values.toSeq
          .filter { p =>
            (p.connectionType.isDefined || p.lastHandshake > 0) &&
              !blacklistedPeers.exists(ip => p.peerSpec.declaredAddress.exists(_.getAddress == ip))
          }
        Random.shuffle(recentlySeenNonBlacklisted).take(howMany)
      }
    }

    case object GetAllPeers extends GetPeers[Map[InetSocketAddress, PeerInfo]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Map[InetSocketAddress, PeerInfo] = knownPeers
    }

    case class RandomPeerExcluding(excludedPeers: Seq[Option[InetSocketAddress]]) extends GetPeers[Option[PeerInfo]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Option[PeerInfo] = {
        val candidates = knownPeers.values.filterNot { p =>
          excludedPeers.contains(p.peerSpec.address) ||
            blacklistedPeers.exists(addr => p.peerSpec.address.map(_.getAddress).contains(addr))
        }.toSeq
        if (candidates.nonEmpty) Some(candidates(Random.nextInt(candidates.size)))
        else None
      }
    }

    case object GetBlacklistedPeers extends GetPeers[Seq[InetAddress]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sparkzContext: SparkzContext): Seq[InetAddress] = blacklistedPeers
    }

  }

}

object PeerManagerRef {

  def props(settings: SparkzSettings, sparkzContext: SparkzContext): Props = {
    Props(new PeerManager(settings, sparkzContext))
  }

  def apply(settings: SparkzSettings, sparkzContext: SparkzContext)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(settings, sparkzContext))
  }

  def apply(name: String, settings: SparkzSettings, sparkzContext: SparkzContext)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(settings, sparkzContext), name)
  }

}
