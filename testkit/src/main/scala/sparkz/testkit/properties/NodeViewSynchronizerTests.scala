package sparkz.testkit.properties

import akka.actor._
import akka.testkit.TestProbe
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import sparkz.core.NodeViewHolder.ReceivableMessages.{GetNodeViewChanges, ModifiersFromRemote}
import sparkz.core.PersistentNodeViewModifier
import sparkz.core.consensus.History.{Equal, Nonsense, Older, Younger}
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.network.NetworkController.ReceivableMessages.{PenalizePeer, SendToNetwork}
import sparkz.core.network.NodeViewSynchronizer.Events.{BetterNeighbourAppeared, NoBetterNeighbour, NodeViewSynchronizerEvent}
import sparkz.core.network.NodeViewSynchronizer.ReceivableMessages._
import sparkz.core.network._
import sparkz.core.network.message._
import sparkz.core.network.peer.PenaltyType
import sparkz.util.serialization._
import sparkz.core.serialization.{BytesSerializable, SparkzSerializer}
import sparkz.core.transaction.state.MinimalState
import sparkz.core.transaction.{MemoryPool, Transaction}
import sparkz.util.SparkzLogging
import sparkz.testkit.generators.{SyntacticallyTargetedModifierProducer, TotallyValidModifierProducer}
import sparkz.testkit.utils.AkkaFixture

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

@SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
trait NodeViewSynchronizerTests[TX <: Transaction,
                                PM <: PersistentNodeViewModifier,
                                ST <: MinimalState[PM, ST],
                                SI <: SyncInfo,
                                HT <: History[PM, SI, HT],
                                MP <: MemoryPool[TX, MP]
] extends AnyPropSpec
  with Matchers
  with SparkzLogging
  with SyntacticallyTargetedModifierProducer[PM, SI, HT]
  with TotallyValidModifierProducer[PM, ST, SI, HT] {

  val historyGen: Gen[HT]
  val memPool: MP

  def nodeViewSynchronizer(implicit system: ActorSystem):
    (ActorRef, SI, PM, TX, ConnectedPeer, TestProbe, TestProbe, TestProbe, TestProbe, SparkzSerializer[PM])

  class SynchronizerFixture extends AkkaFixture {
    @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
    val (node, syncInfo, mod, tx, peer, pchProbe, ncProbe, vhProbe, eventListener, modSerializer) = nodeViewSynchronizer
  }

  // ToDo: factor this out of here and NVHTests?
  private def withFixture(testCode: SynchronizerFixture => Any): Unit = {
    val fixture = new SynchronizerFixture
    try {
      testCode(fixture)
    }
    finally {
      Await.result(fixture.system.terminate(), Duration.Inf)
    }
  }

  property("NodeViewSynchronizer: SuccessfulTransaction") {
    withFixture { ctx =>
      import ctx._
      node ! SuccessfulTransaction[TX](tx)
      ncProbe.fishForMessage(3 seconds) { case m => m.isInstanceOf[SendToNetwork] }
    }
  }

  property("NodeViewSynchronizer: FailedTransaction") {
    withFixture { ctx =>
      import ctx._
      node ! FailedTransaction(tx.id, new Exception, immediateFailure = true)
      // todo: NVS currently does nothing in this case. Should check banning.
    }
  }

  property("NodeViewSynchronizer: SyntacticallySuccessfulModifier") {
    withFixture { ctx =>
      import ctx._
      node ! SyntacticallySuccessfulModifier(mod)
      // todo ? : NVS currently does nothing in this case. Should it do?
    }
  }

  property("NodeViewSynchronizer: SyntacticallyFailedModification") {
    withFixture { ctx =>
      import ctx._
      node ! SyntacticallyFailedModification(mod, new Exception)
      // todo: NVS currently does nothing in this case. Should check banning.
    }
  }

  property("NodeViewSynchronizer: SemanticallySuccessfulModifier") {
    withFixture { ctx =>
      import ctx._
      node ! SemanticallySuccessfulModifier(mod)
      ncProbe.fishForMessage(3 seconds) { case m => m.isInstanceOf[SendToNetwork] }
    }
  }

  property("NodeViewSynchronizer: SemanticallyFailedModification") {
    withFixture { ctx =>
      import ctx._
      node ! SemanticallyFailedModification(mod, new Exception)
      // todo: NVS currently does nothing in this case. Should check banning.
    }
  }

  //TODO rewrite
  ignore("NodeViewSynchronizer: Message: SyncInfoSpec") {
    withFixture { ctx =>
      import ctx._

      val dummySyncInfoMessageSpec = new SyncInfoMessageSpec[SyncInfo](serializer = new SparkzSerializer[SyncInfo]{
        override def parse(r: Reader): SyncInfo = {
          throw new Exception()
        }

        override def serialize(obj: SyncInfo, w: Writer): Unit = {}
      })

      val dummySyncInfo: SyncInfo = new SyncInfo {
        def answer: Boolean = true

        def startingPoints: History.ModifierIds = Seq((mod.modifierTypeId, mod.id))

        type M = BytesSerializable

        def serializer: SparkzSerializer[M] = throw new Exception
      }

      val msgBytes = dummySyncInfoMessageSpec.toBytes(dummySyncInfo)

      node ! Message(dummySyncInfoMessageSpec, Left(msgBytes), Some(peer))
      //    vhProbe.fishForMessage(3 seconds) { case m => m == OtherNodeSyncingInfo(peer, dummySyncInfo) }
    }
  }

  property("NodeViewSynchronizer: OtherNodeSyncingStatus: Nonsense") {
    withFixture { ctx =>
      import ctx._
      node ! OtherNodeSyncingStatus(peer, Nonsense, Seq.empty)
      // NVS does nothing in this case
    }
  }

  property("NodeViewSynchronizer: OtherNodeSyncingStatus: Older") {
    withFixture { ctx =>
      import ctx._
      system.eventStream.subscribe(eventListener.ref, classOf[NodeViewSynchronizerEvent])
      node ! OtherNodeSyncingStatus(peer, Older, Seq.empty)
      eventListener.fishForMessage(3 seconds) { case m => m == BetterNeighbourAppeared }
    }
  }

  property("NodeViewSynchronizer: OtherNodeSyncingStatus: Older and then Younger") {
    withFixture { ctx =>
      import ctx._
      system.eventStream.subscribe(eventListener.ref, classOf[NodeViewSynchronizerEvent])
      node ! OtherNodeSyncingStatus(peer, Older, Seq.empty)
      node ! OtherNodeSyncingStatus(peer, Younger, Seq.empty)
      eventListener.fishForMessage(3 seconds) { case m => m == NoBetterNeighbour }
    }
  }

  property("NodeViewSynchronizer: OtherNodeSyncingStatus: Younger with Non-Empty Extension") {
    withFixture { ctx =>
      import ctx._
      node ! OtherNodeSyncingStatus(peer, Younger, Seq((mod.modifierTypeId, mod.id)))
      ncProbe.fishForMessage(3 seconds) { case m =>
        m match {
          case SendToNetwork(Message(_, Right(InvData(tid, ids)), None), SendToPeer(p))
            if p == peer && tid == mod.modifierTypeId && ids == Seq(mod.id) => true
          case _ => false
        }
      }
    }
  }

  property("NodeViewSynchronizer: OtherNodeSyncingStatus: Equal") {
    withFixture { ctx =>
      import ctx._
      node ! OtherNodeSyncingStatus(peer, Equal, Seq((mod.modifierTypeId, mod.id)))
      // NVS does nothing significant in this case
    }
  }

  property("NodeViewSynchronizer: Message: InvSpec") {
    withFixture { ctx =>
      import ctx._
      val spec = new InvSpec(3)
      val modifiers = Seq(mod.id)
      val msgBytes = spec.toBytes(InvData(mod.modifierTypeId, modifiers))
      node ! Message(spec, Left(msgBytes), Some(peer))
      pchProbe.fishForMessage(5 seconds) {
        case _: Message[_] => true
        case _ => false
      }
    }
  }

  property("NodeViewSynchronizer: Message: RequestModifierSpec") {
    withFixture { ctx =>
      import ctx._
      @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
      val h = historyGen.sample.get
      val mod = syntacticallyValidModifier(h)
      val (newH, _) = h.append(mod).get
      val m = memPool
      val spec = new RequestModifierSpec(3)
      val modifiers = Seq(mod.id)
      val msgBytes = spec.toBytes(InvData(mod.modifierTypeId, modifiers))
      node ! ChangedHistory(newH)
      node ! ChangedMempool(m)
      node ! Message(spec, Left(msgBytes), Option(peer))

      pchProbe.fishForMessage(5 seconds) {
        case _: Message[_] => true
        case _ => false
      }
    }
  }

  property("NodeViewSynchronizer: Message: Non-Asked Modifiers from Remote") {
    withFixture { ctx =>
      import ctx._

      val modifiersSpec = new ModifiersSpec(1024 * 1024)
      val msgBytes = modifiersSpec.toBytes(ModifiersData(mod.modifierTypeId, Map(mod.id -> mod.bytes)))

      node ! Message(modifiersSpec, Left(msgBytes), Option(peer))
      val messages = vhProbe.receiveWhile(max = 3 seconds, idle = 1 second) { case m => m }
      assert(!messages.exists(_.isInstanceOf[ModifiersFromRemote[PM]]))
    }
  }

  property("NodeViewSynchronizer: Message: Asked Modifiers from Remote") {
    withFixture { ctx =>
      import ctx._
      vhProbe.expectMsgType[GetNodeViewChanges]

      val invSpec = new InvSpec(3)
      val invMsgBytes = invSpec.toBytes(InvData(mod.modifierTypeId, Seq(mod.id)))

      val modifiersSpec = new ModifiersSpec(1024 * 1024)
      val modMsgBytes = modifiersSpec.toBytes(ModifiersData(mod.modifierTypeId, Map(mod.id -> mod.bytes)))

      node ! Message(invSpec, Left(invMsgBytes), Option(peer))
      node ! Message(modifiersSpec, Left(modMsgBytes), Option(peer))
      vhProbe.fishForMessage(3 seconds) {
        case m: ModifiersFromRemote[PM] => m.modifiers.toSeq.contains(mod)
        case _ => false
      }
    }
  }

  property("NodeViewSynchronizer: Message - CheckDelivery -  Do not penalize if delivered") {
    withFixture { ctx =>
      import ctx._

      val invSpec = new InvSpec(3)
      val invMsgBytes = invSpec.toBytes(InvData(mod.modifierTypeId, Seq(mod.id)))

      val modifiersSpec = new ModifiersSpec(1024 * 1024)
      val modMsgBytes = modifiersSpec.toBytes(ModifiersData(mod.modifierTypeId, Map(mod.id -> mod.bytes)))

      node ! Message(invSpec, Left(invMsgBytes), Option(peer))
      node ! Message(modifiersSpec, Left(modMsgBytes), Option(peer))
      system.scheduler.scheduleOnce(1 second, node, Message(modifiersSpec, Left(modMsgBytes), Option(peer)))
      val messages = ncProbe.receiveWhile(max = 5 seconds, idle = 1 second) { case m => m }
      assert(!messages.contains(PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.MisbehaviorPenalty)))
    }
  }

  property("NodeViewSynchronizer: ResponseFromLocal") {
    withFixture { ctx =>
      import ctx._
      node ! ResponseFromLocal(peer, mod.modifierTypeId, Seq(mod))
      pchProbe.expectMsgType[Message[_]]
    }
  }

}
