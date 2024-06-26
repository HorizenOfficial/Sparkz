package sparkz.core.transaction.state

import sparkz.util.serialization._
import sparkz.core.serialization.{BytesSerializable, SparkzSerializer}
import sparkz.core.transaction.box._
import sparkz.core.transaction.box.proposition.{ProofOfKnowledgeProposition, PublicKey25519Proposition}
import sparkz.core.transaction.proof.{ProofOfKnowledge, Signature25519}
import sparkz.crypto.signatures.{Ed25519, PrivateKey, PublicKey}

trait Secret extends BytesSerializable {
  self =>
  type S >: self.type <: Secret
  type PK <: ProofOfKnowledgeProposition[S]

  def companion: SecretCompanion[S]

  def instance: S = self

  def publicImage: PK
}

trait SecretCompanion[S <: Secret] {
  type PK = S#PK

  type PR <: ProofOfKnowledge[S, _ <: ProofOfKnowledgeProposition[S]]

  def owns(secret: S, box: Box[_]): Boolean

  def sign(secret: S, message: Array[Byte]): PR

  def verify(message: Array[Byte], publicImage: PK, proof: PR): Boolean

  def generateKeys(randomSeed: Array[Byte]): (S, PK)
}

case class PrivateKey25519(privKeyBytes: PrivateKey, publicKeyBytes: PublicKey) extends Secret {
  require(privKeyBytes.length == Ed25519.KeyLength, s"${privKeyBytes.length} == ${Ed25519.KeyLength}")
  require(publicKeyBytes.length == Ed25519.KeyLength, s"${publicKeyBytes.length} == ${Ed25519.KeyLength}")

  override type S = PrivateKey25519
  override type PK = PublicKey25519Proposition

  override lazy val companion: SecretCompanion[PrivateKey25519] = PrivateKey25519Companion

  override lazy val publicImage: PublicKey25519Proposition = PublicKey25519Proposition(publicKeyBytes)
  override type M = PrivateKey25519

  override def serializer: SparkzSerializer[PrivateKey25519] = PrivateKey25519Serializer
}

object PrivateKey25519Serializer extends SparkzSerializer[PrivateKey25519] {

  override def serialize(obj: PrivateKey25519, w: Writer): Unit = {
    w.putBytes(obj.privKeyBytes)
    w.putBytes(obj.publicKeyBytes)
  }

  override def parse(r: Reader): PrivateKey25519 = {
    PrivateKey25519(PrivateKey @@ r.getBytes(32), PublicKey @@ r.getBytes(32))
  }
}

object PrivateKey25519Companion extends SecretCompanion[PrivateKey25519] {

  override type PR = Signature25519

  override def owns(secret: PrivateKey25519, box: Box[_]): Boolean = {
    box.proposition match {
      case p: PublicKey25519Proposition => java.util.Arrays.equals(p.pubKeyBytes, secret.publicKeyBytes)
      case _ => false
    }
  }

  override def sign(secret: PrivateKey25519, message: Array[Byte]): Signature25519 = {
    Signature25519(Ed25519.sign(secret.privKeyBytes, message))
  }

  override def verify(message: Array[Byte], publicImage: PublicKey25519Proposition, proof: Signature25519): Boolean =
    Ed25519.verify(proof.signature, message, publicImage.pubKeyBytes)

  override def generateKeys(randomSeed: Array[Byte]): (PrivateKey25519, PublicKey25519Proposition) = {
    val pair = Ed25519.createKeyPair(randomSeed)
    val secret: PrivateKey25519 = PrivateKey25519(pair._1, pair._2)
    secret -> secret.publicImage
  }
}