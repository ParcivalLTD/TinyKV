package tinykv.model

import stainless.lang._
import stainless.collection._
import stainless.annotation._

import Bytes._

/** Small facts about lsize / ltake / ldrop / ++ (see Bytes.scala).
  *
  * Each lemma is an ordinary function whose `ensuring` clause is the statement and whose
  * body is the proof: a recursive call is an appeal to the induction hypothesis, and
  * `decreases` names the measure that makes the induction well-founded. Stainless checks
  * every lemma; none is assumed.
  *
  * Several lemmas require `lsize(a) < Long.MaxValue`, i.e. that `a` is shorter than the
  * point where lsize stops counting. The lists they are used on are single records,
  * which are shorter than Int.MaxValue bytes.
  */
object ListLemmas {

  /** ++ is associative. */
  def appendAssoc[T](a: List[T], b: List[T], c: List[T]): Unit = {
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(_, t) => appendAssoc(t, b, c)
    }
  }.ensuring(_ => (a ++ b) ++ c == a ++ (b ++ c))

  /** Appending never makes a list shorter (even once lsize has stopped counting). */
  def appendSizeLower[T](a: List[T], b: List[T]): Unit = {
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(_, t) => appendSizeLower(t, b)
    }
  }.ensuring(_ => lsize(a ++ b) >= lsize(a))

  /** The length of a concatenation is the sum of the lengths, when that sum is below
    * Long.MaxValue. (Written as a subtraction so the precondition itself cannot overflow.)
    */
  def appendSize[T](a: List[T], b: List[T]): Unit = {
    require(lsize(b) < Long.MaxValue - lsize(a))
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(_, t) => appendSize(t, b)
    }
  }.ensuring(_ => lsize(a ++ b) == lsize(a) + lsize(b))

  /** Taking exactly the first list's length out of a concatenation gives back that list. */
  def takeAppend[T](a: List[T], b: List[T]): Unit = {
    require(lsize(a) < Long.MaxValue)
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(x, t) =>
        // a is not saturated, so neither is t, and lsize(a) == lsize(t) + 1
        assert(lsize(t) < Long.MaxValue && lsize(a) == lsize(t) + 1L)
        takeAppend(t, b)
        assert(ltake(a ++ b, lsize(a)) == Cons(x, ltake(t ++ b, lsize(t))))
    }
  }.ensuring(_ => ltake(a ++ b, lsize(a)) == a)

  /** Dropping exactly the first list's length out of a concatenation leaves the second list. */
  def dropAppend[T](a: List[T], b: List[T]): Unit = {
    require(lsize(a) < Long.MaxValue)
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(_, t) => dropAppend(t, b)
    }
  }.ensuring(_ => ldrop(a ++ b, lsize(a)) == b)

  /** Taking a whole list returns it unchanged. */
  def takeAll[T](a: List[T]): Unit = {
    require(lsize(a) < Long.MaxValue)
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(x, t) =>
        assert(lsize(t) < Long.MaxValue && lsize(a) == lsize(t) + 1L)
        takeAll(t)
        assert(ltake(a, lsize(a)) == Cons(x, ltake(t, lsize(t))))
    }
  }.ensuring(_ => ltake(a, lsize(a)) == a)

  /** Dropping a whole list leaves nothing. */
  def dropAll[T](a: List[T]): Unit = {
    require(lsize(a) < Long.MaxValue)
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(_, t) =>
        assert(lsize(t) < Long.MaxValue && lsize(a) == lsize(t) + 1L)
        dropAll(t)
        assert(ldrop(a, lsize(a)) == ldrop(t, lsize(t)))
    }
  }.ensuring(_ => ldrop(a, lsize(a)) == Nil[T]())

  /** Appending never makes the second list shorter either. */
  def appendSizeLowerRight[T](a: List[T], b: List[T]): Unit = {
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(_, t) => appendSizeLowerRight(t, b)
    }
  }.ensuring(_ => lsize(a ++ b) >= lsize(b))

  /** A prefix that reaches past the first list keeps all of it. */
  def takeAppendMore[T](a: List[T], b: List[T], m: Long): Unit = {
    require(lsize(a) < Long.MaxValue && lsize(a) <= m)
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(x, t) =>
        assert(lsize(t) < Long.MaxValue && lsize(a) == lsize(t) + 1L)
        takeAppendMore(t, b, m - 1L)
        assert(ltake(a ++ b, m) == Cons(x, ltake(t ++ b, m - 1L)))
    }
  }.ensuring(_ => ltake(a ++ b, m) == a ++ ltake(b, m - lsize(a)))

  /** A prefix that stops inside the first list never sees the second. */
  def takeAppendLess[T](a: List[T], b: List[T], m: Long): Unit = {
    require(m <= lsize(a))
    decreases(a)
    a match {
      case Nil()      => ()
      case Cons(x, t) =>
        if (m > 0L) {
          assert(m - 1L <= lsize(t))
          takeAppendLess(t, b, m - 1L)
        }
    }
  }.ensuring(_ => ltake(a ++ b, m) == ltake(a, m))

  /** A prefix is never longer than the list it is taken from. */
  def takeSizeLe[T](l: List[T], n: Long): Unit = {
    decreases(l)
    l match {
      case Nil()      => ()
      case Cons(_, t) => if (n > 0L) takeSizeLe(t, n - 1L)
    }
  }.ensuring(_ => lsize(ltake(l, n)) <= lsize(l))
}
