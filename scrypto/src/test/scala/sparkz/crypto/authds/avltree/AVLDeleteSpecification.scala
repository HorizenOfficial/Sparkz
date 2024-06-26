package sparkz.crypto.authds.avltree

import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import sparkz.crypto.authds.avltree.batch._
import sparkz.crypto.authds.{ADKey, ADValue, TwoPartyTests}
import sparkz.crypto.hash.{Blake2b256, Digest32, Sha256}

class AVLDeleteSpecification extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with TwoPartyTests {

  val KL = 26
  val VL = 8


  property("Batch delete") {
    var newProver = new BatchAVLProver[Digest32, Blake2b256.type](KL, Some(VL))

    val aKey = ADKey @@ Sha256("key 1").take(KL)
    val aValue = ADValue @@ Sha256("value 1").take(VL)
    newProver.performOneOperation(Insert(aKey, aValue)).isSuccess shouldBe true
    newProver.generateProof()

    newProver.performOneOperation(Update(aKey, aValue)).isSuccess shouldBe true
    newProver.generateProof()

    newProver.performOneOperation(Remove(aKey)).isSuccess shouldBe true
    newProver.performOneOperation(Update(aKey, aValue)).isSuccess shouldBe false

  }

}
