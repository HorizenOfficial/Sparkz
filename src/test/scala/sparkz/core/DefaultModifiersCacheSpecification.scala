package sparkz.core

import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import sparkz.core.consensus.{History, HistoryReader, ModifierSemanticValidity, SyncInfo}
import sparkz.core.consensus.History.ModifierIds
import sparkz.core.serialization.SparkzSerializer
import sparkz.crypto.hash.Blake2b256

import scala.util.Try

class DefaultModifiersCacheSpecification extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers {

  private class FakeModifier extends PersistentNodeViewModifier {
    override def parentId: sparkz.util.ModifierId = ???
    override val modifierTypeId: ModifierTypeId = ModifierTypeId @@ (0: Byte)
    override def id: sparkz.util.ModifierId = ???
    override type M = this.type
    override def serializer: SparkzSerializer[FakeModifier.this.type] = ???
  }

  private class FakeSyncInfo extends SyncInfo {
    override def startingPoints: ModifierIds = ???
    override type M = this.type
    override def serializer: SparkzSerializer[FakeSyncInfo.this.type] = ???
  }

  private class FakeHr extends HistoryReader[FakeModifier, FakeSyncInfo] {
    override def isEmpty: Boolean = ???
    override def applicableTry(modifier: FakeModifier): Try[Unit] = ???
    override def modifierById(modifierId: sparkz.util.ModifierId): Option[FakeModifier] = ???
    override def isSemanticallyValid(modifierId: sparkz.util.ModifierId): ModifierSemanticValidity = ???
    override def openSurfaceIds(): Seq[sparkz.util.ModifierId] = ???
    override def continuationIds(info: FakeSyncInfo, size: Int): ModifierIds = ???
    override def syncInfo: FakeSyncInfo = ???
    override def compare(other: FakeSyncInfo): History.HistoryComparisonResult = ???
    override type NVCT = this.type
  }

  property("cache - put() and remove() basics") {
    val limit = 3

    val v1 = bytesToId(Blake2b256.hash("1"))
    val v2 = bytesToId(Blake2b256.hash("2"))

    val cache = new DefaultModifiersCache[FakeModifier, FakeHr](limit)

    cache.maxSize shouldBe limit

    cache.put(v1, new FakeModifier)
    cache.contains(v1) shouldBe true
    cache.size shouldBe 1
    cache.put(v1, new FakeModifier)
    cache.size shouldBe 1

    cache.put(v2, new FakeModifier)
    cache.size shouldBe 2
    cache.contains(v2) shouldBe true
    cache.remove(v2)
    cache.size shouldBe 1
    cache.contains(v2) shouldBe false

    cache.put(v2, new FakeModifier)
    cache.size shouldBe 2
    cache.contains(v2) shouldBe true
    cache.remove(v2)
    cache.contains(v2) shouldBe false
    cache.size shouldBe 1
  }

  property("cache has limits") {
    val limit = 3

    val v1 = bytesToId(Blake2b256.hash("1"))
    val v2 = bytesToId(Blake2b256.hash("2"))
    val v3 = bytesToId(Blake2b256.hash("3"))
    val v4 = bytesToId(Blake2b256.hash("4"))

    val cache = new DefaultModifiersCache[FakeModifier, FakeHr](limit)

    cache.maxSize shouldBe limit

    cache.put(v1, new FakeModifier)
    cache.put(v2, new FakeModifier)
    cache.put(v3, new FakeModifier)

    cache.contains(v1) shouldBe true
    cache.size shouldBe limit

    cache.put(v4, new FakeModifier)
    cache.cleanOverfull().length shouldBe 1
    cache.contains(v1) shouldBe false
    cache.size shouldBe limit

    cache.put(v1, new FakeModifier)
    cache.contains(v1) shouldBe true
    cache.cleanOverfull().length shouldBe 1
    cache.contains(v2) shouldBe false
    cache.size shouldBe limit
  }

  property("Cache can clear the right amount of elements based on its limit") {
    val limit = 10
    val cache = new DefaultModifiersCache[FakeModifier, FakeHr](limit)
    val elementsToStore = 200
    for (index <- 1 to elementsToStore) {
      cache.put(bytesToId(Blake2b256.hash(index.toString)), new FakeModifier)
    }

    cache.size shouldBe elementsToStore

    val cleared = cache.cleanOverfull()

    cleared.size shouldBe (elementsToStore - limit)
    cache.size shouldBe limit
  }

  property("Cache can clear the right amount of elements after performing elements removal") {
    val limit = 10
    val cache = new DefaultModifiersCache[FakeModifier, FakeHr](limit)
    val blocksNumber = 200
    for (index <- 1 to blocksNumber) {
      cache.put(bytesToId(Blake2b256.hash(index.toString)), new FakeModifier)
    }
    var expectedCacheSize = blocksNumber
    cache.size shouldBe expectedCacheSize

    for (index <- 1 to blocksNumber) {
      cache.put(bytesToId(Blake2b256.hash(s"$index.$index")), new FakeModifier)
    }

    expectedCacheSize += blocksNumber
    cache.size shouldBe expectedCacheSize

    for (index <- 1 to 10) {
      cache.remove(bytesToId(Blake2b256.hash(index.toString)))
    }
    expectedCacheSize -= 10
    cache.size shouldBe expectedCacheSize

    val cleared = cache.cleanOverfull()

    cleared.size shouldBe (expectedCacheSize - limit)
    expectedCacheSize = limit
    cache.size shouldBe expectedCacheSize
  }
}
