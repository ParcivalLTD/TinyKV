package tinykv.model

import stainless.lang._
import stainless.collection._
import stainless.annotation._
import stainless.math.wrapping

/** The byte-level primitives the Java codec uses, modelled on immutable lists.
  *
  * Lengths and positions are Longs, as in Java (FileChannel.size() and positions are
  * longs). Using Long rather than the BigInt that List.size returns also keeps every
  * length computation in bit-vector arithmetic, which the solver handles well; mixing
  * BigInt and Int stopped Z3 from finding even simple counterexamples.
  *
  * A java.nio.ByteBuffer is a cursor over bytes: every get* reads at the cursor and
  * advances it. Here a cursor is simply "the bytes not read yet", so each get* returns
  * the value read together with the remaining bytes. ByteBuffer's default byte order
  * is BIG_ENDIAN and RecordCodec never changes it, so all multi-byte values are
  * big-endian (most significant byte first).
  *
  * Every `require` below is a condition under which the Java call would throw, so a
  * Stainless "precondition" VC at a call site is exactly a check that the Java call
  * there cannot throw.
  */
object Bytes {

  // ---------------------------------------------------------------- list helpers

  /** The length of a list as a Long. A Java file is at most Long.MaxValue bytes long,
    * so for every list that can stand for a file this is its exact length. For longer
    * lists (which no file can be) it stops at Long.MaxValue instead of overflowing.
    */
  def lsize[T](l: List[T]): Long = {
    decreases(l)
    l match {
      case Nil()      => 0L
      case Cons(_, t) =>
        val s = lsize(t)
        if (s == Long.MaxValue) s else s + 1L
    }
  }.ensuring(res => res >= 0L)

  /** The first n elements (all of them if there are fewer). The postcondition only
    * covers 0 <= n <= length, the only way the model uses it.
    */
  def ltake[T](l: List[T], n: Long): List[T] = {
    decreases(l)
    l match {
      case Nil()      => Nil[T]()
      case Cons(h, t) => if (n <= 0L) Nil[T]() else Cons(h, ltake(t, n - 1L))
    }
  }.ensuring(res => (0L <= n && n <= lsize(l)) ==> (lsize(res) == n))

  /** Everything after the first n elements. When the list is so long that lsize has
    * stopped counting, only a lower bound on the length of the result is known.
    */
  def ldrop[T](l: List[T], n: Long): List[T] = {
    decreases(l)
    l match {
      case Nil()      => Nil[T]()
      case Cons(_, t) => if (n <= 0L) l else ldrop(t, n - 1L)
    }
  }.ensuring(res =>
    (0L <= n && n <= lsize(l)) ==>
      (lsize(res) >= lsize(l) - n && (lsize(l) < Long.MaxValue ==> (lsize(res) == lsize(l) - n))))

  // ---------------------------------------------------------------- writing

  /** ByteBuffer.putInt: 4 bytes, big-endian.
    * `wrapping` turns off Stainless's overflow check for the `.toByte` casts: like
    * Java's (byte) cast, they are meant to keep only the low 8 bits.
    */
  def putInt(x: Int): List[Byte] = wrapping {
    Cons((x >>> 24).toByte, Cons((x >>> 16).toByte, Cons((x >>> 8).toByte, Cons(x.toByte, Nil[Byte]()))))
  }

  /** ByteBuffer.putLong: 8 bytes, big-endian. */
  def putLong(x: Long): List[Byte] = wrapping {
    Cons((x >>> 56).toByte, Cons((x >>> 48).toByte, Cons((x >>> 40).toByte, Cons((x >>> 32).toByte,
    Cons((x >>> 24).toByte, Cons((x >>> 16).toByte, Cons((x >>> 8).toByte, Cons(x.toByte, Nil[Byte]()))))))))
  }

  // ---------------------------------------------------------------- reading
  // The size facts in the `ensuring` clauses are only stated for lists shorter than
  // Long.MaxValue; decode only ever applies these to its 22-byte header and to a payload
  // of at most Int.MaxValue bytes.

  /** ByteBuffer.get(): throws BufferUnderflowException on an empty buffer. */
  def getByte(bs: List[Byte]): (Byte, List[Byte]) = {
    require(lsize(bs) >= 1L)
    bs match {
      case Cons(b, rest) => (b, rest)
    }
  }.ensuring(res => lsize(bs) < Long.MaxValue ==> (lsize(res._2) == lsize(bs) - 1L))

  /** ByteBuffer.getInt(): needs 4 bytes. `b & 0xFF` widens a byte without sign extension. */
  def getInt(bs: List[Byte]): (Int, List[Byte]) = {
    require(lsize(bs) >= 4L)
    bs match {
      case Cons(b0, Cons(b1, Cons(b2, Cons(b3, rest)))) =>
        (((b0 & 0xFF) << 24) | ((b1 & 0xFF) << 16) | ((b2 & 0xFF) << 8) | (b3 & 0xFF), rest)
    }
  }.ensuring(res => lsize(bs) < Long.MaxValue ==> (lsize(res._2) == lsize(bs) - 4L))

  /** ByteBuffer.getLong(): needs 8 bytes. */
  def getLong(bs: List[Byte]): (Long, List[Byte]) = {
    require(lsize(bs) >= 8L)
    bs match {
      case Cons(b0, Cons(b1, Cons(b2, Cons(b3, Cons(b4, Cons(b5, Cons(b6, Cons(b7, rest)))))))) =>
        (((b0 & 0xFFL) << 56) | ((b1 & 0xFFL) << 48) | ((b2 & 0xFFL) << 40) | ((b3 & 0xFFL) << 32) |
         ((b4 & 0xFFL) << 24) | ((b5 & 0xFFL) << 16) | ((b6 & 0xFFL) << 8) | (b7 & 0xFFL), rest)
    }
  }.ensuring(res => lsize(bs) < Long.MaxValue ==> (lsize(res._2) == lsize(bs) - 8L))

  /** `byte[] dst = new byte[n]; buf.get(dst);`
    * new byte[n] throws NegativeArraySizeException if n < 0, and get(dst) throws
    * BufferUnderflowException if fewer than n bytes remain.
    */
  def getBytes(bs: List[Byte], n: Int): (List[Byte], List[Byte]) = {
    require(0 <= n && n.toLong <= lsize(bs))
    (ltake(bs, n.toLong), ldrop(bs, n.toLong))
  }.ensuring(res =>
    lsize(res._1) == n.toLong && (lsize(bs) < Long.MaxValue ==> (lsize(res._2) == lsize(bs) - n.toLong)))

  /** `ByteBuffer b = ByteBuffer.allocate(len); channel.read(b, position + offset); b.flip();`
    * where `file` stands for the bytes of the segment from `position` to EOF.
    *
    * allocate throws IllegalArgumentException("capacity < 0") if len < 0. The second
    * condition, that the requested bytes exist, is the "no out-of-bounds access"
    * property: the codec never asks for bytes past EOF, and so never allocates more
    * than the file holds.
    *
    * Correspondence assumption (see README): FileChannel.read may in principle return
    * fewer bytes than requested even before EOF; RecordCodec does not loop, so the
    * model assumes a read of existing bytes of a regular file returns all of them.
    */
  def readAt(file: List[Byte], offset: Int, len: Int): List[Byte] = {
    require(0 <= offset && 0 <= len && offset.toLong + len.toLong <= lsize(file))
    ltake(ldrop(file, offset.toLong), len.toLong)
  }.ensuring(res => lsize(res) == len.toLong)
}
