package sparkz.core.network.peer

import scorex.util.ScorexLogging
import sparkz.core.app.SparkzContext
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, PeerBucketStorageImpl}
import sparkz.core.network.peer.PeerDatabase.{PeerConfidence, PeerDatabaseValue}
import sparkz.core.network.peer.PenaltyType.DisconnectPenalty
import sparkz.core.settings.NetworkSettings
import sparkz.core.utils.{NetworkUtils, TimeProvider}

import java.net.{InetAddress, InetSocketAddress}
import java.security.SecureRandom
import scala.concurrent.duration._

/**
  * In-memory peer database implementation supporting temporal blacklisting.
  */
final class InMemoryPeerDatabase(settings: NetworkSettings, sparkzContext: SparkzContext)
  extends PeerDatabase with ScorexLogging {

  private val timeProvider = sparkzContext.timeProvider

  private val nKey: Int = new SecureRandom().nextInt()
  private val newBucketConfig: BucketConfig = BucketConfig(buckets = 1024, bucketPositions = 64, bucketSubgroups = 64)
  private val triedBucketConfig: BucketConfig = BucketConfig(buckets = 256, bucketPositions = 64, bucketSubgroups = 8)
  private val triedBucket: PeerBucketStorageImpl = PeerBucketStorageImpl(triedBucketConfig, nKey, timeProvider)
  private val newBucket: PeerBucketStorageImpl = PeerBucketStorageImpl(newBucketConfig, nKey, timeProvider)

  private val bucketManager: BucketManager = new BucketManager(newBucket, triedBucket)

  private val safeInterval = settings.penaltySafeInterval.toMillis

  private var knownPeers: Map[InetSocketAddress, PeerDatabaseValue] = Map.empty

  /**
    * banned peer ip -> ban expiration timestamp
    */
  private var blacklist = Map.empty[InetAddress, TimeProvider.Time]

  /**
    * penalized peer ip -> (accumulated penalty score, last penalty timestamp)
    */
  private var penaltyBook = Map.empty[InetAddress, (Int, Long)]

  // fill database with known peers
  settings.knownPeers.foreach { address =>
    if (!NetworkUtils.isSelf(address, settings.bindAddress, sparkzContext.externalNodeAddress)) {
      knownPeers += address -> PeerDatabaseValue(address, PeerInfo.fromAddress(address), PeerConfidence.High)
    }
  }

  override def get(peer: InetSocketAddress): Option[PeerDatabaseValue] = {
    if (knownPeers.contains(peer)) {
      knownPeers.get(peer)
    } else {
      bucketManager.getPeer(peer) match {
        case Some(peerBucketValue) => Some(peerBucketValue.peerDatabaseValue)
        case _ => None
      }
    }
  }

  override def addOrUpdateKnownPeer(peerDatabaseValue: PeerDatabaseValue): Unit = {
    if (peerIsNotBlacklistedAndNotKnownPeer(peerDatabaseValue)) {
      bucketManager.addOrUpdatePeerIntoBucket(peerDatabaseValue)
    }
  }

  private def peerIsNotBlacklistedAndNotKnownPeer(peerDatabaseValue: PeerDatabaseValue): Boolean = {
    !isBlacklisted(peerDatabaseValue.address) &&
      !knownPeers.contains(peerDatabaseValue.address)
  }

  override def addOrUpdateKnownPeers(peersDatabaseValue: Seq[PeerDatabaseValue]): Unit = {
    val validPeers = peersDatabaseValue.filterNot { p =>
      isBlacklisted(p.address)
    }
    validPeers.foreach(peer => addOrUpdateKnownPeer(peer))
  }

  override def addToBlacklist(socketAddress: InetSocketAddress,
                              penaltyType: PenaltyType): Unit = {
    remove(socketAddress)
    Option(socketAddress.getAddress).foreach { address =>
      penaltyBook -= address
      if (!blacklist.keySet.contains(address))
        blacklist += address -> (timeProvider.time() + penaltyDuration(penaltyType))
      else log.warn(s"${address.toString} is already blacklisted")
    }
  }

  override def removeFromBlacklist(address: InetAddress): Unit = {
    log.info(s"$address removed from blacklist")
    blacklist -= address
  }

  override def remove(address: InetSocketAddress): Unit = {
    bucketManager.removePeer(address)
  }

  override def allPeers: Map[InetSocketAddress, PeerDatabaseValue] = knownPeers ++ bucketManager.getTriedPeers ++ bucketManager.getNewPeers

  override def blacklistedPeers: Seq[InetAddress] = blacklist
    .map { case (address, bannedTill) =>
      checkBanned(address, bannedTill)
      address
    }
    .toSeq

  override def isEmpty: Boolean = bucketManager.isEmpty

  override def isBlacklisted(address: InetAddress): Boolean =
    blacklist.get(address).exists(checkBanned(address, _))

  def isBlacklisted(address: InetSocketAddress): Boolean =
    Option(address.getAddress).exists(isBlacklisted)

  /**
    * Registers a new penalty in the penalty book.
    *
    * @return - `true` if penalty threshold is reached, `false` otherwise.
    */
  override def peerPenaltyScoreOverThreshold(socketAddress: InetSocketAddress, penaltyType: PenaltyType): Boolean =
    Option(socketAddress.getAddress).exists { address =>
      val (newPenaltyScore, penaltyTs) = penaltyBook.get(address) match {
        case Some((penaltyScoreAcc, lastPenaltyTs)) =>
          val currentTime = timeProvider.time()
          if (currentTime - lastPenaltyTs - safeInterval > 0 || penaltyType.isPermanent)
            (penaltyScoreAcc + penaltyType.penaltyScore, timeProvider.time())
          else
            (penaltyScoreAcc, lastPenaltyTs)
        case None =>
          (penaltyType.penaltyScore, timeProvider.time())
      }
      if (newPenaltyScore > settings.penaltyScoreThreshold)
        true
      else {
        penaltyBook += address -> (newPenaltyScore -> penaltyTs)
        false
      }
    }

  /**
    * Currently accumulated penalty score for a given address.
    */
  def penaltyScore(address: InetAddress): Int =
    penaltyBook.getOrElse(address, (0, 0L))._1

  def penaltyScore(socketAddress: InetSocketAddress): Int =
    Option(socketAddress.getAddress).map(penaltyScore).getOrElse(0)

  private def checkBanned(address: InetAddress, bannedTill: Long): Boolean = {
    val stillBanned = timeProvider.time() < bannedTill
    if (!stillBanned) removeFromBlacklist(address)
    stillBanned
  }

  private def penaltyDuration(penalty: PenaltyType): Long =
    penalty match {
      case PenaltyType.NonDeliveryPenalty | PenaltyType.MisbehaviorPenalty | PenaltyType.SpamPenalty | _: DisconnectPenalty =>
        settings.temporalBanDuration.toMillis
      case PenaltyType.PermanentPenalty =>
        (360 * 10).days.toMillis
    }

  override def randomPeersSubset: Map[InetSocketAddress, PeerDatabaseValue] = knownPeers ++ bucketManager.getRandomPeers

  override def updatePeer(peerDatabaseValue: PeerDatabaseValue): Unit = {
    if (peerIsNotBlacklistedAndNotKnownPeer(peerDatabaseValue)) {
      bucketManager.makeTried(peerDatabaseValue)
    }
  }
}
