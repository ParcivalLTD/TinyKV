package tinykv.model

import stainless.lang.{Success => _, _}
import stainless.collection._
import stainless.annotation._

import Bytes._
import ByteLemmas._
import Crc._
import Format._
import ListLemmas._

/** Model of SegmentManager.recoverDataSegment (SegmentManager.java:124-152) and P4.
  *
  * The Java loop reads a record at `pos`, hands it to the index, and advances `pos` by
  * bytesRead. Here the loop is a recursion on the bytes from `pos` to EOF. What happens
  * to the index (tombstones, putIfNewer) is not modelled: `records` is the sequence of
  * records the loop hands to the index, in order.
  */
object Recovery {

  sealed abstract class RecoveryResult
  /** The loop ended normally. The segment now ends `validLength` bytes after the point
    * where this (recursive) call started: at EOF, or where it was truncated.
    */
  case class Recovered(records: List[LogRecord], validLength: Long) extends RecoveryResult
  /** recoverDataSegment threw CorruptedRecordException (l.147). */
  case class Fatal() extends RecoveryResult

  /** `input` is the segment from `pos` to EOF. The precondition says the segment is shorter
    * than Long.MaxValue bytes, which every file is (a file's size is a long).
    */
  def recover(input: List[Byte], isTail: Boolean): RecoveryResult = {
    require(lsize(input) < Long.MaxValue)
    decreases(lsize(input))
    Codec.decode(input) match {                                 // l.129
      case CleanEof() => Recovered(Nil[LogRecord](), 0L)        // l.130: break
      case Success(r, n) =>                                     // l.131-139
        Integrity.p3Integrity(input) // proof step, not behaviour: 22 <= n <= lsize(input), so the loop advances
        recover(ldrop(input, n.toLong), isTail) match {         // l.139: pos += bytesRead
          case Recovered(rs, len) => Recovered(Cons(r, rs), n.toLong + len)
          case Fatal()            => Fatal()
        }
      case Corrupted(torn) =>                                   // l.140
        if (isTail && torn) Recovered(Nil[LogRecord](), 0L)     // l.141-145: truncate(pos)
        else Fatal()                                            // l.146-148: throw
    }
  }.ensuring(res => res match {
    case Recovered(_, len) => 0L <= len && len <= lsize(input)
    case Fatal()           => true
  })

  // ---------------------------------------------------------------- the fault model

  def allEncodable(rs: List[LogRecord]): Boolean = rs match {
    case Nil()      => true
    case Cons(r, t) => encodable(r) && allEncodable(t)
  }

  /** The log as written: encode(r1) ++ encode(r2) ++ ... ++ encode(rn). */
  def encodeAll(rs: List[LogRecord]): List[Byte] = {
    require(allEncodable(rs))
    decreases(rs)
    rs match {
      case Nil()      => Nil[Byte]()
      case Cons(r, t) => encode(r) ++ encodeAll(t)
    }
  }

  /** k: how many of the records lie entirely within the first m bytes of the log.
    * Record i lies inside iff |encode(r1)| + ... + |encode(ri)| <= m; that sum grows with
    * i, so these records are exactly r1..rk, and this function counts them by
    * subtracting each record's length from m while it still fits.
    */
  def wholeRecords(rs: List[LogRecord], m: Long): BigInt = {
    require(allEncodable(rs))
    decreases(rs)
    rs match {
      case Nil()      => BigInt(0)
      case Cons(r, t) =>
        if (lsize(encode(r)) <= m) 1 + wholeRecords(t, m - lsize(encode(r))) else BigInt(0)
    }
  }.ensuring(res => res >= 0)

  /** |encode(r1)| + ... + |encode(rk)| for that same k: where the last whole record ends. */
  def wholeBytes(rs: List[LogRecord], m: Long): Long = {
    require(allEncodable(rs) && 0L <= m)
    decreases(rs)
    rs match {
      case Nil()      => 0L
      case Cons(r, t) =>
        if (lsize(encode(r)) <= m) lsize(encode(r)) + wholeBytes(t, m - lsize(encode(r))) else 0L
    }
  }.ensuring(res => 0L <= res && res <= m)

  // ---------------------------------------------------------------- proofs

  /** A crash while a record is being written leaves a strict, non-empty prefix of its
    * bytes at the end of the file. decode reports any such prefix as a torn tail.
    */
  def tornPrefix(r: LogRecord, m: Long): Unit = {
    require(encodable(r) && 0L < m && m < lsize(encode(r)))
    val k = r.key
    val v = r.value
    val kLen = lsize(k).toInt
    val vLen = lsize(v).toInt
    val tc = typeCode(r.tpe)
    val c = crc(r.timestamp, tc, k, v)
    val a5 = putInt(vLen)
    val a4 = putInt(kLen) ++ a5
    val a3 = Cons(tc, a4)
    val a2 = putLong(r.timestamp) ++ a3
    val a1 = putInt(c) ++ a2
    val h = Cons(DATA_MAGIC, a1)
    assert(h == header(c, r.timestamp, tc, kLen, vLen))
    headerSize(c, r.timestamp, tc, kLen, vLen)
    val body = k ++ v
    appendSize(k, v)
    appendSize(h, body)
    assert(encode(r) == h ++ body)
    assert(lsize(ltake(encode(r), m)) == m)

    if (m >= HEADER_SIZE.toLong) {
      // The whole header made it to disk, so decode parses the real lengths and finds
      // that the record they announce runs past EOF.
      takeAppendMore(h, body, m)
      val x = ltake(body, m - HEADER_SIZE.toLong)
      assert(ltake(encode(r), m) == h ++ x)
      takeAppend(h, x)
      assert(readAt(h ++ x, 0, HEADER_SIZE) == h)
      getPutInt(c, a2)
      getPutLong(r.timestamp, a3)
      getPutInt(kLen, a5)
      getPutInt(vLen, Nil[Byte]())
    }
  }.ensuring(_ => Codec.decode(ltake(encode(r), m)) == Corrupted(true))

  /** P4. Write records r1..rn to the tail segment; a crash keeps an arbitrary prefix of
    * the first m bytes. Recovery then returns exactly r1..rk, where k is the number of
    * records lying entirely within those m bytes, and truncates the segment right after
    * rk. It returns no other record: none is invented, none is lost.
    */
  def p4Recovery(rs: List[LogRecord], m: Long): Unit = {
    require(allEncodable(rs) && lsize(encodeAll(rs)) < Long.MaxValue && 0L <= m)
    decreases(rs)
    takeSizeLe(encodeAll(rs), m) // the crashed file is no longer than the log
    rs match {
      case Nil() => ()
      case Cons(r, t) =>
        val e = encode(r)
        val tail = encodeAll(t)
        appendSizeLower(e, tail)
        appendSizeLowerRight(e, tail)
        if (lsize(e) <= m) {
          // r lies entirely within the prefix: decode returns it (P1), and the loop
          // continues on the rest of the prefix, which is a prefix of the remaining log.
          takeAppendMore(e, tail, m)
          val restPrefix = ltake(tail, m - lsize(e))
          RoundTrip.p1RoundTrip(r, restPrefix)
          dropAppend(e, restPrefix)
          p4Recovery(t, m - lsize(e)) // induction hypothesis
        } else {
          // the crash cut r short: the prefix ends inside r, before any later record
          takeAppendLess(e, tail, m)
          if (m > 0L) tornPrefix(r, m)
        }
    }
  }.ensuring(_ =>
    recover(ltake(encodeAll(rs), m), true) ==
      Recovered(rs.take(wholeRecords(rs, m)), wholeBytes(rs, m)))
}
