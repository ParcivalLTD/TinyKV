package tinykv.model

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/** The checksum. This file holds the model's ONLY trusted assumption.
  *
  * `@extern` tells Stainless to ignore the body (`???`) and treat `crc` as an
  * uninterpreted function: the only thing the prover knows about it is that it is a
  * function, i.e. equal arguments give equal results. Every proof in this directory
  * therefore holds for ANY deterministic checksum, CRC32 included. Conversely, nothing
  * here says anything about how well CRC32 detects errors: that is not proven.
  *
  * Java counterpart: RecordCodec.computeCrc (RecordCodec.java), which feeds
  * ts, type, key length, value length, key bytes and value bytes into
  * java.util.zip.CRC32. The lengths are not separate arguments here because they are
  * determined by `key` and `value`.
  *
  * Java returns the CRC32 value as a `long` in [0, 2^32). encode() stores its low
  * 32 bits, and readRecordAt() compares it with Integer.toUnsignedLong(stored int).
  * For a value in [0, 2^32) that long comparison is the same as comparing the low
  * 32 bits as ints, so the model returns those 32 bits directly as an `Int`.
  */
object Crc {
  @extern
  def crc(timestamp: Long, typeCode: Byte, key: List[Byte], value: List[Byte]): Int = ???
}
