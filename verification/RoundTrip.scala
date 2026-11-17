package tinykv.model

import stainless.lang.{Success => _, _}
import stainless.collection._
import stainless.annotation._

import Bytes._
import ByteLemmas._
import Crc._
import Format._
import ListLemmas._

/** P1 (round trip) for the fixed model (Codec.decode). The proof is the same as in
  * RoundTripBeforeFix: the fix only changes how the total length is computed, and for a
  * record encode can write, both computations give the same value.
  */
object RoundTrip {

  def p1RoundTrip(r: LogRecord, rest: List[Byte]): Unit = {
    require(encodable(r))
    val k = r.key
    val v = r.value
    val kLen = lsize(k).toInt // the KEY_LEN encode writes
    val vLen = lsize(v).toInt
    val tc = typeCode(r.tpe)
    val c = crc(r.timestamp, tc, k, v)

    // The header bytes, split at the points where decode's cursor stops (Format.header).
    val a5 = putInt(vLen)
    val a4 = putInt(kLen) ++ a5
    val a3 = Cons(tc, a4)
    val a2 = putLong(r.timestamp) ++ a3
    val a1 = putInt(c) ++ a2
    val h = Cons(DATA_MAGIC, a1)
    assert(h == header(c, r.timestamp, tc, kLen, vLen))
    headerSize(c, r.timestamp, tc, kLen, vLen)

    // Lengths: |k ++ v| = kLen + vLen, |encode(r)| = 22 + kLen + vLen
    val body = k ++ v
    appendSize(k, v)
    appendSize(h, body)
    assert(encode(r) == h ++ body)
    assert(lsize(encode(r)) == HEADER_SIZE.toLong + kLen.toLong + vLen.toLong)

    val input = encode(r) ++ rest
    appendAssoc(h, body, rest)
    appendSizeLower(encode(r), rest) // rest can only make the input longer
    assert(input == h ++ (body ++ rest))

    // l.108-110: the first 22 bytes are the header we wrote
    takeAppend(h, body ++ rest)
    assert(readAt(input, 0, HEADER_SIZE) == h)

    // l.112-121: each get* returns the field written by the matching put*
    getPutInt(c, a2)
    getPutLong(r.timestamp, a3)
    getPutInt(kLen, a5)
    getPutInt(vLen, Nil[Byte]())

    // l.132-134: the payload is exactly key ++ value
    dropAppend(h, body ++ rest)
    takeAppend(body, rest)
    assert(readAt(input, HEADER_SIZE, kLen + vLen) == body)

    // l.136-139: splitting the payload at kLen gives back key and value
    takeAppend(k, v)
    dropAppend(k, v)
    takeAll(v)
    dropAll(v)
    assert(getBytes(body, kLen) == ((k, v)))
    assert(getBytes(v, vLen) == ((v, Nil[Byte]())))
  }.ensuring(_ => Codec.decode(encode(r) ++ rest) match {
    case Success(r2, n) => r2 == r && n.toLong == lsize(encode(r))
    case _              => false
  })
}
