package tinykv.model

import stainless.lang._
import stainless.collection._
import stainless.annotation._

import Bytes._
import Format._
import ListLemmas._

/** Facts about the big-endian put/get primitives and the header. Proven, not assumed. */
object ByteLemmas {

  /** Reading 4 bytes back after putInt returns the same int and leaves the rest untouched. */
  def getPutInt(x: Int, rest: List[Byte]): Unit = {
    ()
  }.ensuring(_ => getInt(putInt(x) ++ rest) == ((x, rest)))

  /** Same for 8-byte longs. */
  def getPutLong(x: Long, rest: List[Byte]): Unit = {
    ()
  }.ensuring(_ => getLong(putLong(x) ++ rest) == ((x, rest)))

  /** The encoded header is HEADER_SIZE = 1 + 4 + 8 + 1 + 4 + 4 = 22 bytes long. */
  def headerSize(c: Int, ts: Long, tc: Byte, kLen: Int, vLen: Int): Unit = {
    assert(lsize(putInt(kLen)) == 4L)
    assert(lsize(putLong(ts)) == 8L)
    appendSize(putInt(kLen), putInt(vLen))
    appendSize(putLong(ts), Cons(tc, putInt(kLen) ++ putInt(vLen)))
    appendSize(putInt(c), putLong(ts) ++ Cons(tc, putInt(kLen) ++ putInt(vLen)))
  }.ensuring(_ => lsize(header(c, ts, tc, kLen, vLen)) == HEADER_SIZE.toLong)
}
