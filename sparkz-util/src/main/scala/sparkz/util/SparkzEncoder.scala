package sparkz.util

import sparkz.util.encode.{Base16, BytesEncoder}

import scala.util.Try

class SparkzEncoder extends BytesEncoder {
  @inline
  override val Alphabet: String = Base16.Alphabet

  @inline
  override def encode(input: Array[Byte]): String = Base16.encode(input)

  @inline
  override def decode(input: String): Try[Array[Byte]] = Base16.decode(input)

  /**
    * This method might be useful and reimplemented, if encoding of ModifierId and VersionTag
    * is different form default bytes encoding, e.g. this method should be reimplemented together
    * with encode() and decode methods
    */
  @inline
  def encode(input: String): String = input

  /**
    * This method might be useful and reimplemented, if encoding of ModifierId and VersionTag
    * is different form default bytes encoding, e.g. this method should be reimplemented together
    * with encode() and decode methods
    */
  @inline
  def encodeVersion(input: String): String = input

  /**
    * This method might be useful and reimplemented, if encoding of ModifierId and VersionTag
    * is different form default bytes encoding, e.g. this method should be reimplemented together
    * with encode() and decode methods
    */
  @inline
  def encodeId(input: ModifierId): String = input

}

object SparkzEncoder {
  val default: SparkzEncoder = new SparkzEncoder()
}
