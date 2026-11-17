package tinykv.model

import stainless.lang.{Success => _, _}
import stainless.collection._
import stainless.annotation._

import Bytes._
import Crc._
import Format._

/** P3 (integrity) for the fixed model: what a Success guarantees about the input. */
object Integrity {

  /** The CRC32 field as stored on disk: bytes 1..4 of the record, big-endian. */
  def storedChecksum(input: List[Byte]): Int = {
    require(lsize(input) >= 5L)
    getInt(ldrop(input, 1L))._1
  }

  /** decode reads the checksum from its 22-byte copy of the header (`readAt(input, 0, 22)`,
    * then one byte for MAGIC); those are the same four bytes as bytes 1..4 of the input.
    */
  def checksumIsBytes1to4(input: List[Byte]): Unit = {
    require(lsize(input) >= HEADER_SIZE.toLong)
    input match {
      case Cons(b0, Cons(b1, Cons(b2, Cons(b3, Cons(b4, rest))))) =>
        assert(ldrop(input, 0L) == input)
        assert(ltake(input, 22L) == Cons(b0, Cons(b1, Cons(b2, Cons(b3, Cons(b4, ltake(rest, 17L)))))))
        assert(ldrop(input, 1L) == Cons(b1, Cons(b2, Cons(b3, Cons(b4, rest)))))
    }
  }.ensuring(_ => getInt(getByte(readAt(input, 0, HEADER_SIZE))._2)._1 == storedChecksum(input))

  /** P3. If decode returns Success(r, n), then
    *  - n == 22 + |key| + |value|, the length of the record as encode writes it,
    *  - n <= the number of input bytes (decode never claims bytes that are not there),
    *  - the checksum stored in bytes 1..4 equals crc of the decoded fields.
    */
  def p3Integrity(input: List[Byte]): Unit = {
    if (lsize(input) >= HEADER_SIZE.toLong) checksumIsBytes1to4(input)
  }.ensuring(_ => Codec.decode(input) match {
    case Success(r, n) =>
      n.toLong == HEADER_SIZE.toLong + lsize(r.key) + lsize(r.value) &&
      n.toLong <= lsize(input) &&
      storedChecksum(input) == crc(r.timestamp, typeCode(r.tpe), r.key, r.value)
    case _ => true
  })
}
