package sparkz.core.network

import akka.actor._
import akka.util.Timeout
import sparkz.core.network.NetworkController.ReceivableMessages.{GetFilteredConnectedPeers, PenalizePeer, RegisterMessageSpecs, SendToNetwork}
import sparkz.core.network.message.Message
import sparkz.core.network.message.Message.MessageCode
import sparkz.core.network.peer.PenaltyType
import sparkz.core.utils.ActorHelper
import sparkz.util.SparkzLogging

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationLong, FiniteDuration, SECONDS}
import scala.language.postfixOps

/**
  * Wraps NetworkController actor, providing verification of request-response communication with other peers
  */
class RequestTracker(
                      networkControllerRef: ActorRef,
                      trackedRequestCode: MessageCode,
                      trackedResponseCode: MessageCode,
                      deliveryTimeout: FiniteDuration,
                      penalizeNonDelivery: Boolean)(implicit ec: ExecutionContext)
  extends Actor with SparkzLogging with ActorHelper {

  private var handler: Option[ActorRef] = None

  private val requestTracker = mutable.Set.empty[(MessageCode, ConnectedPeer)]

  implicit val timeout: Timeout = Timeout(60, SECONDS)

  override def receive: Receive = {
    registerMessageSpec orElse
      sendTrackedRequest orElse
      receiveResponse orElse
      verifyDelivery orElse
      forward
  }

  private def registerMessageSpec: Receive = {
    case RegisterMessageSpecs(specs, handler) =>
      this.handler = Some(handler)
      networkControllerRef ! RegisterMessageSpecs(specs, self)
  }

  private def sendTrackedRequest: Receive = {
    case message@SendToNetwork(m@Message(spec, _, _), strategy) if spec.messageCode == trackedRequestCode =>
      val start = System.nanoTime
      askActor[Seq[ConnectedPeer]](networkControllerRef, GetFilteredConnectedPeers(strategy, m.spec.protocolVersion))
        .map {
          _.foreach { peer =>
            val requestKey = (trackedRequestCode, peer)

            requestTracker += requestKey

            networkControllerRef ! message.copy(sendingStrategy = SendToPeer(peer))

            val elapsed = (System.nanoTime - start).nano
            context.system.scheduler.scheduleOnce(deliveryTimeout - elapsed, self, VerifyDelivery(requestKey, peer))
          }
        }
  }

  private def receiveResponse: Receive = {
    case m@Message(spec, _, Some(remote)) if spec.messageCode == trackedResponseCode =>
      //verify we asked with requestTracker
      if (requestTracker.remove((trackedRequestCode, remote))) {
        handler.foreach(actor => actor ! m)
      } else {
        networkControllerRef ! PenalizePeer(remote.connectionId.remoteAddress, PenaltyType.SpamPenalty)
      }
  }

  private def verifyDelivery: Receive = {
    case VerifyDelivery(key, remote) =>
      if (requestTracker.remove(key) && penalizeNonDelivery) {
        networkControllerRef ! PenalizePeer(remote.connectionId.remoteAddress, PenaltyType.NonDeliveryPenalty)
      }
  }

  private def forward: Receive = {
    case any if sender() == networkControllerRef =>
      handler.foreach(actor => actor ! any)
    case any =>
      networkControllerRef ! any
  }

  case class VerifyDelivery(requestKey: (MessageCode, ConnectedPeer), remote: ConnectedPeer)
}

object RequestTrackerRef {
  def props(networkControllerRef: ActorRef, trackedRequestCode: MessageCode, trackedResponseCode: MessageCode, deliveryTimeout: FiniteDuration, penalizeNonDelivery: Boolean)(implicit ec: ExecutionContext): Props = {
    Props(new RequestTracker(networkControllerRef, trackedRequestCode, trackedResponseCode, deliveryTimeout, penalizeNonDelivery))
  }

  def apply(networkControllerRef: ActorRef, trackedRequestCode: MessageCode, trackedResponseCode: MessageCode, deliveryTimeout: FiniteDuration, penalizeNonDelivery: Boolean)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    system.actorOf(props(networkControllerRef, trackedRequestCode, trackedResponseCode, deliveryTimeout, penalizeNonDelivery))
  }
}

