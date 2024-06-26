package sparkz.testkit

import sparkz.core.{PersistentNodeViewModifier, TransactionsCarryingPersistentNodeViewModifier}
import sparkz.core.consensus.{History, SyncInfo}
import sparkz.core.transaction.box.Box
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.{BoxTransaction, MemoryPool}
import sparkz.mid.state.BoxMinimalState
import sparkz.testkit.generators.AllModifierProducers
import sparkz.testkit.properties._
import sparkz.testkit.properties.mempool.{MempoolFilterPerformanceTest, MempoolRemovalTest, MempoolTransactionsTest}
import sparkz.testkit.properties.state.StateApplicationTest
import sparkz.testkit.properties.state.box.{BoxStateApplyChangesTest, BoxStateChangesGenerationTest, BoxStateRollbackTest}

/**
  * The idea of this class is to get some generators and test some situations, common for all blockchains
  */
trait BlockchainSanity[P <: Proposition,
TX <: BoxTransaction[P, B],
PM <: PersistentNodeViewModifier,
CTM <: PM with TransactionsCarryingPersistentNodeViewModifier[TX],
SI <: SyncInfo,
B <: Box[P],
MPool <: MemoryPool[TX, MPool],
ST <: BoxMinimalState[P, B, TX, PM, ST],
HT <: History[PM, SI, HT]]
  extends
    BoxStateChangesGenerationTest[P, TX, PM, B, ST]
    with StateApplicationTest[PM, ST]
    with HistoryTests[TX, PM, SI, HT]
    with BoxStateApplyChangesTest[P, TX, PM, B, ST]
    with WalletSecretsTest[P, TX, PM]
    with BoxStateRollbackTest[P, TX, PM, CTM, B, ST]
    with MempoolTransactionsTest[TX, MPool]
    with MempoolFilterPerformanceTest[TX, MPool]
    with MempoolRemovalTest[TX, MPool, PM, CTM, HT, SI]
    with AllModifierProducers[TX, MPool, PM, CTM, ST, SI, HT]
    with NodeViewHolderTests[TX, PM, ST, SI, HT, MPool]
    with NodeViewSynchronizerTests[TX, PM, ST, SI, HT, MPool] {
}
