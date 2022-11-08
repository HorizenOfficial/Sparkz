package sparkz.core.app

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import scorex.util.ScorexLogging
import sparkz.core.api.http.{ApiErrorHandler, ApiRejectionHandler, ApiRoute, CompositeHttpService}
import sparkz.core.network._
import sparkz.core.network.message._
import sparkz.core.network.peer.PeerBucketStorage.{BucketConfig, NewPeerBucketStorage, TriedPeerBucketStorage}
import sparkz.core.network.peer.{BucketManager, InMemoryPeerDatabase, PeerManagerRef}
import sparkz.core.settings.SparkzSettings
import sparkz.core.transaction.Transaction
import sparkz.core.utils.NetworkTimeProvider
import sparkz.core.{NodeViewHolder, PersistentNodeViewModifier}

import java.net.InetSocketAddress
import java.security.SecureRandom
import scala.concurrent.ExecutionContext

trait Application extends ScorexLogging {

  import sparkz.core.network.NetworkController.ReceivableMessages.ShutdownNetwork

  type TX <: Transaction
  type PMOD <: PersistentNodeViewModifier
  type NVHT <: NodeViewHolder[TX, PMOD]

  //settings
  implicit val settings: SparkzSettings

  //api
  val apiRoutes: Seq[ApiRoute]

  implicit def exceptionHandler: ExceptionHandler = ApiErrorHandler.exceptionHandler
  implicit def rejectionHandler: RejectionHandler = ApiRejectionHandler.rejectionHandler

  protected implicit lazy val actorSystem: ActorSystem = ActorSystem(settings.network.agentName)
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("sparkz.executionContext")

  protected val features: Seq[PeerFeature]
  protected val additionalMessageSpecs: Seq[MessageSpec[_]]
  private val featureSerializers: PeerFeature.Serializers = features.map(f => f.featureId -> f.serializer).toMap

  private lazy val basicSpecs = {
    val invSpec = new InvSpec(settings.network.maxInvObjects)
    val requestModifierSpec = new RequestModifierSpec(settings.network.maxInvObjects)
    val modifiersSpec = new ModifiersSpec(settings.network.maxModifiersSpecMessageSize)
    Seq(
      GetPeersSpec,
      new PeersSpec(featureSerializers, settings.network.maxPeerSpecObjects),
      invSpec,
      requestModifierSpec,
      modifiersSpec
    )
  }

  val nodeViewHolderRef: ActorRef
  val nodeViewSynchronizer: ActorRef

  /** API description in openapi format in YAML or JSON */
  val swaggerConfig: String

  val timeProvider = new NetworkTimeProvider(settings.ntp)

  //an address to send to peers
  lazy val externalSocketAddress: Option[InetSocketAddress] = {
    settings.network.declaredAddress
  }

  val sparkzContext: SparkzContext = SparkzContext(
    messageSpecs = basicSpecs ++ additionalMessageSpecs,
    features = features,
    timeProvider = timeProvider,
    externalNodeAddress = externalSocketAddress
  )

  private val secureRandom = new SecureRandom()
  private val nKey: Int = secureRandom.nextInt()
  private val newBucketConfig: BucketConfig = BucketConfig(buckets = 1024, bucketPositions = 64, bucketSubgroups = 64)
  private val triedBucketConfig: BucketConfig = BucketConfig(buckets = 256, bucketPositions = 64, bucketSubgroups = 8)
  private val triedBucket: TriedPeerBucketStorage = TriedPeerBucketStorage(triedBucketConfig, nKey, timeProvider)
  private val newBucket: NewPeerBucketStorage = NewPeerBucketStorage(newBucketConfig, nKey, timeProvider)

  private val bucketManager: BucketManager = new BucketManager(newBucket, triedBucket)
  private val peerDatabase = new InMemoryPeerDatabase(settings.network, sparkzContext.timeProvider, bucketManager)
  val peerManagerRef: ActorRef = PeerManagerRef(settings, sparkzContext, peerDatabase)

  val networkControllerRef: ActorRef = NetworkControllerRef(
    "networkController", settings.network, peerManagerRef, sparkzContext)

  val peerSynchronizer: ActorRef = PeerSynchronizerRef("PeerSynchronizer",
    networkControllerRef, peerManagerRef, settings.network, featureSerializers)

  lazy val combinedRoute: Route = CompositeHttpService(actorSystem, apiRoutes, settings.restApi, swaggerConfig).compositeRoute

  def run(): Unit = {
    require(settings.network.agentName.length <= Application.ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at ${settings.restApi.bindAddress.toString}")

    val bindAddress = settings.restApi.bindAddress

    Http().newServerAt(bindAddress.getAddress.getHostAddress, bindAddress.getPort).bindFlow(combinedRoute)

    //on unexpected shutdown
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        log.error("Unexpected shutdown")
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    networkControllerRef ! ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate().onComplete { _ =>
      log.info("Exiting from the app...")
      System.exit(0)
    }
  }
}

object Application {

  val ApplicationNameLimit: Int = 50
}
