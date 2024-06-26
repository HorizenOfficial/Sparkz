package sparkz.core

import java.security.SecureRandom

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

package object utils {

  /**
    * @param block - function to profile
    * @return - execution time in seconds and function result
    */
  def profile[R](block: => R): (Float, R) = {
    val t0 = System.nanoTime()
    val result = block // call-by-name
    val t1 = System.nanoTime()
    ((t1 - t0).toFloat / 1000000000, result)
  }

  def toTry(b: Boolean, msg: String): Try[Unit] = if (b) {
    Success(())
  } else {
    Failure(new Exception(msg))
  }

  @tailrec
  final def untilTimeout[T](timeout: FiniteDuration,
                            delay: FiniteDuration = 100.milliseconds)(fn: => T): T = {
    Try {
      fn
    } match {
      case Success(x) => x
      case _ if timeout > delay =>
        Thread.sleep(delay.toMillis)
        untilTimeout(timeout - delay, delay)(fn)
      case Failure(e) => throw e
    }
  }

  def randomBytes(howMany: Int): Array[Byte] = {
    val r = new Array[Byte](howMany)
    new SecureRandom().nextBytes(r) //overrides r
    r
  }

  def concatBytes(seq: Iterable[Array[Byte]]): Array[Byte] = {
    val length: Int = seq.map(_.length).sum
    val result: Array[Byte] = new Array[Byte](length)
    var pos: Int = 0
    seq.foreach { array =>
      System.arraycopy(array, 0, result, pos, array.length)
      pos += array.length
    }
    result
  }

  def concatFixLengthBytes(seq: Iterable[Array[Byte]]): Array[Byte] = seq.headOption match {
    case None => Array[Byte]()
    case Some(head) => concatFixLengthBytes(seq, head.length)
  }


  def concatFixLengthBytes(seq: Iterable[Array[Byte]], length: Int): Array[Byte] = {
    val result: Array[Byte] = new Array[Byte](seq.toSeq.length * length)
    var index = 0
    seq.foreach { s =>
      Array.copy(s, 0, result, index, length)
      index += length
    }
    result
  }

}
