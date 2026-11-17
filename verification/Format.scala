package tinykv.model

import stainless.lang.{Success => _, _}
import stainless.collection._
import stainless.annotation._

import Bytes._
import Crc._

/** The on-disk record format and its encoder; shared by the model before and after the fix.
  *
  * Frame (RecordCodec.java, class comment), big-endian, 22-byte header:
  *   [MAGIC 1B = 0x4B][CRC32 4B][TIMESTAMP 8B][TYPE 1B][KEY_LEN 4B][VAL_LEN 4B][KEY][VAL]
  */
object Format {

  val DATA_MAGIC: Byte = 0x4B // RecordCodec.DATA_MAGIC ('K')
  val HEADER_SIZE: Int = 22   // RecordCodec.HEADER_SIZE = 1 + 4 + 8 + 1 + 4 + 4

  /** Record.Type: PUT has code 1, DELETE has code 2. */
  sealed abstract class RecType
  case object Put extends RecType
  case object Delete extends RecType

  /** Record.Type.getCode */
  def typeCode(t: RecType): Byte = t match {
    case Put    => 1
    case Delete => 2
  }

  /** Record.Type.fromCode: Java returns null for an unknown code; None here. */
  def fromCode(b: Byte): Option[RecType] =
    if (b == 1) Some[RecType](Put)
    else if (b == 2) Some[RecType](Delete)
    else None[RecType]()

  /** Record.LogRecord, minus its `crc` field: encode() ignores that field and decode()
    * only copies the stored checksum into it, so it carries no information of its own.
    *
    * The class invariant mirrors Record.Key's constructor, which throws
    * IllegalArgumentException("Key cannot be empty") (Record.java:32). Stainless checks it
    * at every construction, so the model also proves decode never builds an empty key.
    */
  case class LogRecord(timestamp: Long, tpe: RecType, key: List[Byte], value: List[Byte]) {
    require(key.nonEmpty)
  }

  /** RecordCodec.ReadResult, minus `position` (the caller's input) and the `reason`
    * string, which only feed log messages.
    */
  sealed abstract class ReadResult
  case class Success(record: LogRecord, bytesRead: Int) extends ReadResult
  case class CleanEof() extends ReadResult
  case class Corrupted(isTornTail: Boolean) extends ReadResult

  /** The records Java's encode() accepts. encode() allocates
    * ByteBuffer.allocate(HEADER_SIZE + kBytes.length + vBytes.length) in 32-bit int
    * arithmetic; past Int.MaxValue that sum wraps and encode() throws (negative capacity,
    * or a too-small buffer and then BufferOverflowException), so nothing is written.
    * The first two conjuncts only keep the Long sum in the third from overflowing.
    */
  def encodable(r: LogRecord): Boolean =
    lsize(r.key) <= Int.MaxValue.toLong && lsize(r.value) <= Int.MaxValue.toLong &&
    HEADER_SIZE.toLong + lsize(r.key) + lsize(r.value) <= Int.MaxValue.toLong

  /** The 22 header bytes, in the order of RecordCodec.encode's put calls (lines 50-55). */
  def header(crcValue: Int, ts: Long, tc: Byte, kLen: Int, vLen: Int): List[Byte] =
    Cons(DATA_MAGIC, putInt(crcValue) ++ (putLong(ts) ++ Cons(tc, putInt(kLen) ++ putInt(vLen))))

  /** RecordCodec.encode (RecordCodec.java:43-60). */
  def encode(r: LogRecord): List[Byte] = {
    require(encodable(r))
    val kLen: Int = lsize(r.key).toInt    // l.44: kBytes.length (fits in an Int: see encodable)
    val vLen: Int = lsize(r.value).toInt  // l.45: vBytes.length
    val c: Int = crc(r.timestamp, typeCode(r.tpe), r.key, r.value)           // l.48 and l.51
    header(c, r.timestamp, typeCode(r.tpe), kLen, vLen) ++ (r.key ++ r.value) // l.50-57
  }
}
