package sparkz.core.transaction.proof

import sparkz.util.serialization._
import sparkz.core.serialization.SparkzSerializer
import sparkz.core.transaction.box.proposition.PublicKey25519Proposition
import sparkz.core.transaction.state.PrivateKey25519
import sparkz.util.SparkzEncoding
import sparkz.crypto.signatures.{Ed25519, Signature}

/**
  * @param signature 25519 signature
  */
case class Signature25519(signature: Signature) extends ProofOfKnowledge[PrivateKey25519, PublicKey25519Proposition]
  with SparkzEncoding {

  require(signature.isEmpty || signature.length == Ed25519.SignatureLength,
    s"${signature.length} != ${Ed25519.SignatureLength}")

  override def isValid(proposition: PublicKey25519Proposition, message: Array[Byte]): Boolean =
    Ed25519.verify(signature, message, proposition.pubKeyBytes)

  override type M = Signature25519

  override def serializer: SparkzSerializer[Signature25519] = Signature25519Serializer

  override def toString: String = s"Signature25519(${encoder.encode(signature)})"
}

object Signature25519Serializer extends SparkzSerializer[Signature25519] {

  override def serialize(obj: Signature25519, w: Writer): Unit = {
    w.putBytes(obj.signature)
  }

  override def parse(r: Reader): Signature25519 = {
    Signature25519(Signature @@ r.getBytes(Ed25519.SignatureLength))
  }
}

object Signature25519 {
  lazy val SignatureSize = Ed25519.SignatureLength
}
