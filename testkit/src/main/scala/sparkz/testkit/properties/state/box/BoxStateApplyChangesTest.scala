package sparkz.testkit.properties.state.box

import org.scalacheck.Gen
import sparkz.core._
import sparkz.core.transaction.BoxTransaction
import sparkz.core.transaction.box.Box
import sparkz.core.transaction.box.proposition.Proposition
import sparkz.core.transaction.state.BoxStateChanges
import sparkz.mid.state.BoxMinimalState

import scala.util.Random

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
trait BoxStateApplyChangesTest[P <: Proposition,
TX <: BoxTransaction[P, B],
PM <: PersistentNodeViewModifier,
B <: Box[P],
ST <: BoxMinimalState[P, B, TX, PM, ST]] extends BoxStateTests[P, B, TX, PM, ST] {

  def stateChangesGenerator(state: ST): Gen[BoxStateChanges[P, B]]

  property("BoxMinimalState should be able to add and remove boxes") {
    forAll(stateGen, minSuccessful(2)) { state =>
      val changes = stateChangesGenerator(state).sample.get
      changes.toAppend.foreach { insertion =>
        state.closedBox(insertion.box.id).isDefined shouldBe false
      }
      changes.toRemove.foreach { removal =>
        state.closedBox(removal.boxId).isDefined shouldBe true
      }
      val newVersion = bytesToVersion(Array.fill(32)(Random.nextInt(Byte.MaxValue).toByte))
      val newState = state.applyChanges(changes, newVersion).get
      changes.toAppend.foreach { insertion =>
        newState.closedBox(insertion.box.id).isDefined shouldBe true
      }
      changes.toRemove.foreach { removal =>
        newState.closedBox(removal.boxId).isDefined shouldBe false
      }
    }

  }
}
