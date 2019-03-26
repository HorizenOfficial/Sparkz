package scorex.core.network.peer

import java.net.InetSocketAddress

import scorex.core.settings.ScorexSettings
import scorex.core.utils.TimeProvider
import scorex.util.ScorexLogging

/**
  * In-memory peer database implementation supporting temporal blacklisting.
  */
final class InMemoryPeerDatabase(settings: ScorexSettings, timeProvider: TimeProvider)
  extends PeerDatabase with ScorexLogging {

  private val defaultBanDuration = settings.network.misbehaviorBanDuration.toMillis

  private var peers = Map.empty[InetSocketAddress, PeerInfo]

  // banned peer ip -> ban expiration timestamp
  private var blacklist = Map.empty[InetSocketAddress, TimeProvider.Time]

  override def get(peer: InetSocketAddress): Option[PeerInfo] = peers.get(peer)

  override def addOrUpdateKnownPeer(peerInfo: PeerInfo): Unit = {
    if (!peerInfo.peerSpec.declaredAddress.exists(isBlacklisted)) {
      peerInfo.peerSpec.address.foreach { address =>
        peers += address -> peerInfo
      }
    }
  }

  override def addToBlacklist(address: InetSocketAddress,
                              banDuration: Long = defaultBanDuration): Unit = {
    peers -= address
    if (!blacklist.contains(address)) {
      log.info(s"$address blacklisted")
      blacklist += address -> (timeProvider.time() + banDuration)
    }
  }

  override def removeFromBlacklist(address: InetSocketAddress): Unit = {
    log.info(s"$address is to be removed from blacklist")
    blacklist -= address
  }

  override def remove(address: InetSocketAddress): Unit = {
    log.info(s"$address is to be removed from known peers")
    peers -= address
  }

  override def isBlacklisted(address: InetSocketAddress): Boolean = {
    blacklist.get(address).exists { bannedTil =>
      val stillBanned = timeProvider.time() < bannedTil
      if (!stillBanned) removeFromBlacklist(address)
      stillBanned
    }
  }

  override def knownPeers: Map[InetSocketAddress, PeerInfo] = peers

  override def blacklistedPeers: Seq[InetSocketAddress] = blacklist.keys.toSeq

  override def isEmpty: Boolean = peers.isEmpty

}
