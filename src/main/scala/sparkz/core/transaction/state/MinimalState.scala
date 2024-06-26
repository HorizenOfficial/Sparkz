package sparkz.core.transaction.state

import sparkz.core.transaction._
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.{PersistentNodeViewModifier, VersionTag}
import sparkz.core.PersistentNodeViewModifier

import scala.util.Try

/**
  * Abstract functional interface of state which is a result of a sequential blocks applying
  */
trait MinimalState[M <: PersistentNodeViewModifier, MS <: MinimalState[M, MS]] extends StateReader {
  self: MS =>

  def applyModifier(mod: M): Try[MS]

  def rollbackTo(version: VersionTag): Try[MS]

  /**
    * @return read-only copy of this state
    */
  def getReader: StateReader = this

}


trait StateFeature

trait TransactionValidation[TX <: Transaction] extends StateFeature {
  def isValid(tx: TX): Boolean = validate(tx).isSuccess

  def filterValid(txs: Seq[TX]): Seq[TX] = txs.filter(isValid)

  def validate(tx: TX): Try[Unit]
}

trait ModifierValidation[M <: PersistentNodeViewModifier] extends StateFeature {
  def validate(mod: M): Try[Unit]
}

trait BalanceSheet[P <: Proposition] extends StateFeature {
  def balance(id: P, height: Option[Int] = None): Long
}

trait AccountTransactionsHistory[P <: Proposition, TX <: Transaction] extends StateFeature {
  def accountTransactions(id: P): Array[TX]
}
