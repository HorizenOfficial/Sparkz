package sparkz.testkit.properties.mempool

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import sparkz.core.{PersistentNodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.transaction.{MemoryPool, Transaction}
import sparkz.testkit.TestkitHelpers
import sparkz.testkit.generators.ArbitraryTransactionsCarryingModifierProducer
import sparkz.util.SparkzLogging

trait MempoolRemovalTest[
TX <: Transaction,
MPool <: MemoryPool[TX, MPool],
PM <: PersistentNodeViewModifier,
CTM <: PM with TransactionsCarryingPersistentNodeViewModifier[TX],
HT <: History[PM, SI, HT],
SI <: SyncInfo] extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers
  with SparkzLogging
  with TestkitHelpers
  with MemoryPoolTest[TX, MPool]
  with ArbitraryTransactionsCarryingModifierProducer[TX, MPool, PM, CTM] {

  val historyGen: Gen[HT]

  //todo: this test doesn't check anything. It should be reworked as a test for node view holder
  property("Transactions once added to block should be removed from Mempool") {
    val min = 1
    val max = 10
    forAll(Gen.choose(min, max)) { noOfTransactionsFromMempool: Int =>
      var m: MPool = memPool
      @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
      var h: HT = historyGen.sample.get
      forAll(transactionGenerator) { tx: TX =>
        m = m.put(tx).get
      }
      // var prevMempoolSize = m.size
      val b = modifierWithTransactions(Some(m), None)
      //todo: fix    (m.size + b.transactions.get.size) shouldEqual prevMempoolSize
    }
  }
}


