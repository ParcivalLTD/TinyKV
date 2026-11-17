package tinykv.model

import stainless.lang.{Success => _, _}
import stainless.collection._
import stainless.annotation._

import Bytes._
import Crc._
import Format._

/** Line-by-line model of RecordCodec.readRecordAt AFTER the overflow fix.
  * Line numbers refer to RecordCodec.java on this branch.
  *
  * The only change from CodecBeforeFix is the length check (l.128-137):
  *  - the total record length is computed in long arithmetic: 22 plus two non-negative
  *    ints is at most 2^32 + 20, far from overflowing a long;
  *  - an explicit bound rejects a total above Int.MaxValue. Only a file larger than
  *    2 GiB can get past the EOF check with such a length, and the int-sized buffer and
  *    Success.bytesRead that follow could not hold it. encode never writes such a record
  *    (see Format.encodable), so it is reported as corruption, not as a torn write.
  * After the bound, kLen + vLen <= Int.MaxValue - 22, so the int addition at l.139 is safe.
  *
  * `input` stands for the segment's bytes from `position` to EOF, so Java's
  * `long remaining = size - position` is `lsize(input)`, also a Long.
  *
  * An unknown type code is rejected with Corrupted(false) (l.152-157), as
  * RecordCodec.decode also does (l.92-95).
  */
object Codec {

  def decode(input: List[Byte]): ReadResult = {
    val remaining: Long = lsize(input)                                // l.101, l.104
    if (remaining == 0L) CleanEof()                                   // l.102: position >= size
    else if (remaining < HEADER_SIZE.toLong) Corrupted(true)          // l.105-107
    else {
      val header = readAt(input, 0, HEADER_SIZE)                      // l.109-111
      val (magic, h1) = getByte(header)                               // l.113
      if (magic != DATA_MAGIC) Corrupted(false)                       // l.114-116
      else {
        val (storedCrc, h2) = getInt(h1)                              // l.118
        val (ts, h3) = getLong(h2)                                    // l.119
        val (tc, h4) = getByte(h3)                                    // l.120
        val (kLen, h5) = getInt(h4)                                   // l.121
        val (vLen, _) = getInt(h5)                                    // l.122
        if (kLen <= 0 || vLen < 0) Corrupted(false)                   // l.124-126
        else {
          val total: Long = HEADER_SIZE.toLong + kLen.toLong + vLen.toLong // l.129: long arithmetic
          if (remaining < total) Corrupted(true)                      // l.130-132
          else if (total > Int.MaxValue.toLong) Corrupted(false)      // l.135-137: explicit bound
          else {
            val payload = readAt(input, HEADER_SIZE, kLen + vLen)     // l.139-141
            val (kBytes, p1) = getBytes(payload, kLen)                // l.143-144
            val (vBytes, _) = getBytes(p1, vLen)                      // l.145-146
            if (crc(ts, tc, kBytes, vBytes) != storedCrc)             // l.148
              Corrupted(total >= remaining)                           // l.149: position + total >= size
            else fromCode(tc) match {                                 // l.152
              case None()  => Corrupted(false)                        // l.153-157
              case Some(t) => Success(LogRecord(ts, t, kBytes, vBytes), total.toInt) // l.158
            }
          }
        }
      }
    }
  }
}
