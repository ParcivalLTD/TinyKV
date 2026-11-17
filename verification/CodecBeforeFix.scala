package tinykv.model

import stainless.lang.{Success => _, _}
import stainless.collection._
import stainless.annotation._

import Bytes._
import Crc._
import Format._

/** Line-by-line model of RecordCodec.readRecordAt AS IT WAS BEFORE THE FIX
  * (commit 1695f13, RecordCodec.java:99-147). Line numbers below refer to that commit.
  *
  * This file is kept unchanged as the evidence for verification/first-run.txt.
  * The fixed model is in Codec.scala.
  *
  * `input` stands for the segment's bytes from `position` to EOF, so Java's
  * `long remaining = size - position` is `lsize(input)`, also a Long.
  *
  * One deliberate difference from readRecordAt: an unknown type code is rejected with
  * Corrupted(false), as RecordCodec.decode does (l.91-94). readRecordAt itself passes
  * Type.fromCode's null on to the LogRecord constructor, which throws a
  * NullPointerException (l.145-146 and Record.java:62).
  */
object CodecBeforeFix {

  def decode(input: List[Byte]): ReadResult = {
    val remaining: Long = lsize(input)                                // l.100, l.103
    if (remaining == 0L) CleanEof()                                   // l.101: position >= size
    else if (remaining < HEADER_SIZE.toLong) Corrupted(true)          // l.104-106
    else {
      val header = readAt(input, 0, HEADER_SIZE)                      // l.108-110
      val (magic, h1) = getByte(header)                               // l.112
      if (magic != DATA_MAGIC) Corrupted(false)                       // l.113-115
      else {
        val (storedCrc, h2) = getInt(h1)                              // l.117
        val (ts, h3) = getLong(h2)                                    // l.118
        val (tc, h4) = getByte(h3)                                    // l.119
        val (kLen, h5) = getInt(h4)                                   // l.120
        val (vLen, _) = getInt(h5)                                    // l.121
        if (kLen <= 0 || vLen < 0) Corrupted(false)                   // l.123-125
        else {
          val total: Int = HEADER_SIZE + kLen + vLen                  // l.127: 32-bit int arithmetic
          if (remaining < total.toLong) Corrupted(true)               // l.128-130: int widened to long
          else {
            val payload = readAt(input, HEADER_SIZE, kLen + vLen)     // l.132-134: allocate(kLen + vLen)
            val (kBytes, p1) = getBytes(payload, kLen)                // l.136-137
            val (vBytes, _) = getBytes(p1, vLen)                      // l.138-139
            if (crc(ts, tc, kBytes, vBytes) != storedCrc)             // l.141
              Corrupted(total.toLong >= remaining)                    // l.142: position + total >= size
            else fromCode(tc) match {                                 // l.145
              case None()  => Corrupted(false)                        // decode() l.92-94 (see above)
              case Some(t) => Success(LogRecord(ts, t, kBytes, vBytes), total) // l.146
            }
          }
        }
      }
    }
  }
}
