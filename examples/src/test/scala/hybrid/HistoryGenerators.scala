package hybrid

import examples.hybrid.blocks.PowBlock
import examples.hybrid.history.{HistoryStorage, HybridHistory}
import examples.hybrid.mining.HybridSettings
import org.scalacheck.Gen
import sparkz.core.transaction.box.proposition.PublicKey25519Proposition
import sparkz.core.utils.NetworkTimeProvider
import sparkz.crypto.signatures.PublicKey

import scala.concurrent.ExecutionContext.Implicits.global

trait HistoryGenerators {
  this: StoreGenerators =>

  def settings: HybridSettings

  private val historyTimestamp = 1478164225796L
  private val historyNonce = -308545845552064644L
  private val historyBrothersCount = 0
  private val historyBrothersHash = Array.fill(32)(0: Byte)
  private val historyBrothers = Seq.empty
  private val historyProposition = PublicKey25519Proposition(PublicKey @@ sparkz.utils.Random.randomBytes(32))

  private lazy val genesisBlock = PowBlock(
    settings.mining.GenesisParentId,
    settings.mining.GenesisParentId,
    historyTimestamp,
    historyNonce,
    historyBrothersCount,
    historyBrothersHash,
    historyProposition,
    historyBrothers)

  val historyGen: Gen[HybridHistory] = lsmStoreGen.map { blockStorage =>
    val storage = new HistoryStorage(blockStorage, settings.mining)
    //we don't care about validation here
    val validators = Seq()
    new HybridHistory(storage, settings.mining, validators, None, new NetworkTimeProvider(settings.sparkzSettings.ntp))
      .append(genesisBlock).get._1
      .ensuring(_.modifierById(genesisBlock.id).isDefined)
  }
}
