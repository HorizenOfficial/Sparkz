package sparkz.core.serialization

import java.nio.ByteBuffer
import akka.util.ByteString
import sparkz.util.ByteArrayBuilder
import sparkz.util.serialization._
import sparkz.util.serialization.{VLQByteStringReader, VLQByteStringWriter}

import scala.util.Try

trait SparkzSerializer[T] extends Serializer[T, T, Reader, Writer] {

  def toByteString(obj: T): ByteString = {
    val writer = new VLQByteStringWriter()
    serialize(obj, writer)
    writer.result()
  }

  def parseByteString(byteString: ByteString): T = {
    val reader = new VLQByteStringReader(byteString)
    parse(reader)
  }

  def parseByteStringTry(byteString: ByteString): Try[T] = {
    Try(parseByteString(byteString))
  }

  def toBytes(obj: T): Array[Byte] = {
    val writer = new VLQByteBufferWriter(new ByteArrayBuilder())
    serialize(obj, writer)
    writer.result().toBytes
  }

  def parseBytes(bytes: Array[Byte]): T = {
    val reader = new VLQByteBufferReader(ByteBuffer.wrap(bytes))
    parse(reader)
  }

  def parseBytesTry(bytes: Array[Byte]): Try[T] = {
    Try(parseBytes(bytes))
  }

}
