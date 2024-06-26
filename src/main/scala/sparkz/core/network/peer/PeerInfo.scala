package sparkz.core.network.peer

import java.net.InetSocketAddress

import sparkz.core.app.Version
import sparkz.core.network.{ConnectionDirection, PeerSpec}

/**
  * Information about peer to be stored in PeerDatabase
  *
  * @param peerSpec       - general information about the peer
  * @param lastHandshake  - timestamp when last handshake was done
  * @param connectionType - type of connection (Incoming/Outgoing) established to this peer if any
  */
case class PeerInfo(peerSpec: PeerSpec,
                    lastHandshake: Long,
                    connectionType: Option[ConnectionDirection] = None) {
  override def hashCode(): Int = super.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case other: PeerInfo => peerSpec == other.peerSpec && lastHandshake == other.lastHandshake && connectionType == other.connectionType
    case _ => false
  }
}

/**
  * Information about P2P layer status
  *
  * @param lastIncomingMessage - timestamp of last received message from any peer
  * @param currentNetworkTime  - current network time
  */
case class PeersStatus(lastIncomingMessage: Long, currentNetworkTime: Long)

object PeerInfo {

  /**
    * Create peer info from address only, when we don't know other fields
    * (e.g. we got this information from config or from API)
    */
  def fromAddress(address: InetSocketAddress): PeerInfo = {
    val peerSpec = PeerSpec("unknown", Version.initial, s"unknown-$address", Some(address), Seq())
    PeerInfo(peerSpec, 0L, None)
  }

}