# A verified model of TinyKV's record codec

This directory holds a Scala model of TinyKV's on-disk record codec and crash recovery,
and machine-checked proofs about that model, made with
[Stainless](https://github.com/epfl-lara/stainless) 0.10.4.

**The accurate claim:** a model of the record codec is verified and corresponds to the
Java by inspection. TinyKV itself is not verified: the link between the Scala model and
the Java source was checked by hand (see [Correspondence](#correspondence-scala--java)),
and only the codec and the recovery loop are modelled.

## What is proven

The record frame (big-endian, 22-byte header):

```
[MAGIC 1B = 0x4B][CRC32 4B][TIMESTAMP 8B][TYPE 1B: 1=PUT, 2=DELETE][KEY_LEN 4B][VAL_LEN 4B][KEY][VAL]
```

`decode` below is the model of `RecordCodec.readRecordAt` after the fix (`Codec.decode`).
Its input is the segment's bytes from the read position to end of file. It returns
`Success(record, bytesRead)`, `CleanEof()` or `Corrupted(isTornTail)`.
`lsize` is a list's length as a `Long` (see [Assumptions](#assumptions-and-modelling-choices)).

| | Statement (as checked) | Where |
|---|---|---|
| **P1** Round trip | For every record `r` with a non-empty key and `22 + |key| + |value| <= Int.MaxValue`, and for every byte list `rest`: `decode(encode(r) ++ rest) == Success(r, n)` with `n == lsize(encode(r))`. | `RoundTrip.p1RoundTrip` (fixed model), `RoundTripBeforeFix.p1RoundTrip` (model before the fix) |
| **P2** No crash | `decode` has no precondition, and every verification condition inside it is valid. So for **any** byte list it terminates and returns one of the three results, with no integer overflow, no read past the end of the input, no negative or oversized buffer allocation, and no empty key. | The 25 VCs of `Codec.decode` |
| **P3** Integrity | If `decode(input) == Success(r, n)` then `n == 22 + |r.key| + |r.value|`, `n <= lsize(input)`, and the checksum stored in bytes 1..4 of the input equals `crc(r.timestamp, type, r.key, r.value)`. | `Integrity.p3Integrity` |
| **P4** Recovery under torn writes | Let the tail segment hold `encode(r1) ++ ... ++ encode(rn)`, and let a crash keep only its first `m` bytes (any `m >= 0`). Then `recover(prefix) == Recovered(r1..rk, end of rk)`, where `k` is the number of records lying entirely within those `m` bytes. Recovery returns no other record (none invented, none lost) and truncates the segment right after `rk`. | `Recovery.p4Recovery` |

Notes on the statements:

- **P1** holds on the model of the code *before* the fix too. The overflow needs a header
  whose lengths sum past `Int.MaxValue`, and `encode` never writes one. That is why the
  round-trip tests never found the bug.
- **P2** is not a separate lemma. A Stainless function with no `require` must be safe on
  every input, and Stainless generates a check (a VC) for every operation that could
  fail. For `Codec.decode` these 25 VCs are: 3 overflow checks on additions,
  2 checks on the `Long`-to-`Int` conversion of the total, 18 preconditions of the byte
  readers (each is the condition under which the Java call would throw), the
  non-empty-key invariant, and match exhaustiveness. "Returns one of the three results"
  follows from the result type.
- **P3**: `kLen` and `vLen` in "n == 22 + kLen + vLen" are the lengths of the decoded key
  and value. P1 shows `decode` really does return `Success` on encoded records, so P3 is
  not vacuous.
- **P4**: `k` is computed by `wholeRecords`, which subtracts each record's length from `m`
  while it still fits. Record `i` lies inside the prefix exactly when
  `|encode(r1)| + ... + |encode(ri)| <= m`, and that sum grows with `i`, so the
  records inside are exactly `r1..rk`. The key step, `Recovery.tornPrefix`, proves that
  `decode` reports every strict non-empty prefix of an encoded record as
  `Corrupted(isTornTail = true)`.

### Fault model (P4)

- Records are appended to the **tail** segment in order, each written by `encode`.
- A crash loses an arbitrary **suffix** of what was written: the file keeps its first `m`
  bytes, for any `m`, including a cut in the middle of a header.
- Not covered: changes to bytes that were already written (bit flips, a damaged length
  field; see [Step 5](#step-5-a-damaged-length-in-the-middle-of-the-tail-segment)),
  writes that reach disk out of order, sealed segments, hint files, and what the index
  does with the recovered records.

## Assumptions and modelling choices

The trusted base is Stainless itself (its Scala 3 front end, Inox, and the Z3 and cvc5
solvers), plus the following.

**In the proofs.** The only unproven construct is the checksum:

- `Crc.crc` is declared `@extern`: Stainless treats it as an uninterpreted function, so
  all it knows is that equal arguments give equal results. Every proof therefore holds
  for any deterministic checksum. **Nothing is proven about CRC32's error detection.**
- There is no `assume()` in any file, and `crc` is the only `@extern`
  (`grep -n 'assume(\|@extern' *.scala`).
- `wrapping { ... }` appears in `Bytes.putInt` and `Bytes.putLong` only. It switches
  off Stainless's overflow check for the `.toByte` casts, which, like Java's `(byte)`
  cast, deliberately keep the low 8 bits.
- `Recovery.recover` calls the lemma `Integrity.p3Integrity`. The call has no effect; it
  makes P3's facts available to prove that the loop advances (termination).

**In the correspondence with the Java, checked by hand:**

1. *Scala mirrors Java.* Each line of `Codec.decode`, `CodecBeforeFix.decode` and
   `Recovery.recover` carries the number of the Java line it models; see the table below.
   Nothing checks this mechanically.
2. *File contents as `List[Byte]`.* The model's input is the segment's bytes from the read
   position to end of file, so Java's `long remaining = size - position` is
   `lsize(input)`.
3. *Lengths are `Long`s.* `lsize` counts up to `Long.MaxValue` and then stops. Every Java
   file is shorter than that, so for any file `lsize` is the exact length. (`List.size`
   is a `BigInt`; mixing `BigInt` with the `Int` header fields left the solvers unable to
   prove or refute even simple goals, and Java uses `long` for file sizes anyway.)
4. *Reads return all requested bytes.* `FileChannel.read` may in principle return fewer
   bytes than requested; `readRecordAt` does not loop, so the model assumes that reading
   existing bytes of a regular file returns all of them.
5. *Checksum width.* Java's `computeCrc` returns the CRC32 as a `long` in `[0, 2^32)`;
   `encode` stores its low 32 bits, and `readRecordAt` compares it with
   `Integer.toUnsignedLong(stored)`. For values in that range this is the same as
   comparing the 32-bit ints, which is what the model does.
6. *Unknown type codes.* The model rejects a type code other than 1 or 2 with
   `Corrupted(false)`, as `RecordCodec.decode` does (RecordCodec.java:92-95).
   **`readRecordAt` itself does not**: `Type.fromCode` returns `null` and the
   `LogRecord` constructor throws a `NullPointerException` (RecordCodec.java:152-153,
   Record.java:62). P2 therefore does not carry over to the Java for a record that has a
   valid CRC but an unknown type code. That path is unchanged and is a candidate
   follow-up fix.
7. *Simplified result types.* `ReadResult.Corrupted` has no `position` (always the
   caller's position) and no `reason` (only logged). `LogRecord` has no `crc` field:
   `encode` ignores it and `decode` only copies the stored checksum into it.
8. *Domain of `encode`.* `Format.encodable` (`22 + |key| + |value| <= Int.MaxValue`) is
   exactly the set of records Java's `encode` accepts. Beyond it, `encode`'s `int`
   capacity wraps and it throws, so nothing is written. Non-empty keys are a class
   invariant, mirroring `Record.Key`'s constructor.
9. *Recovery.* The loop is a recursion over the bytes after `pos`. Index updates
   (tombstones, `putIfNewer`) are not modelled. `records` is the sequence handed to the
   index, in order.
10. *`RecordCodec.decode(ByteBuffer)`* (used by `Segment.readRecord`) is not modelled,
    except for its unknown-type check. Its half of the overflow fix is covered by tests,
    not by proof.

## Correspondence: Scala → Java

Java line numbers refer to this branch, except for `CodecBeforeFix`.

| Scala | Java | Lines |
|---|---|---|
| `Crc.crc` | `RecordCodec.computeCrc` | RecordCodec.java:217-225 |
| `Format.DATA_MAGIC`, `Format.HEADER_SIZE` | `RecordCodec.DATA_MAGIC`, `HEADER_SIZE` | RecordCodec.java:24, 26 |
| `Format.RecType`, `typeCode`, `fromCode` | `Record.Type`, `getCode`, `fromCode` | Record.java:14-24 |
| `Format.LogRecord` (invariant: non-empty key) | `Record.LogRecord`, `Record.Key` constructor | Record.java:60-65, 32 |
| `Format.ReadResult` | `RecordCodec.ReadResult` | RecordCodec.java:31-35 |
| `Format.encodable` | when `encode`'s `allocate` succeeds | RecordCodec.java:46 |
| `Format.header` | `encode`'s `put` calls for the header | RecordCodec.java:50-55 |
| `Format.encode` | `RecordCodec.encode` | RecordCodec.java:43-60 |
| `Bytes.lsize` | `channel.size()` and `size - position` | RecordCodec.java:101, 104 |
| `Bytes.putInt`, `putLong` | `ByteBuffer.putInt`, `putLong` (big-endian) | RecordCodec.java:51-55 |
| `Bytes.getByte`, `getInt`, `getLong` | `ByteBuffer.get`, `getInt`, `getLong` | RecordCodec.java:113, 118-122 |
| `Bytes.getBytes` | `new byte[n]; payload.get(dst)` | RecordCodec.java:143-146 |
| `Bytes.readAt` | `ByteBuffer.allocate` + `channel.read` + `flip` | RecordCodec.java:109-111, 139-141 |
| `Codec.decode` | `RecordCodec.readRecordAt` (+ `decode`'s type check) | RecordCodec.java:100-154 (+ 92-95), line by line in comments |
| `CodecBeforeFix.decode` | `RecordCodec.readRecordAt` before the fix | RecordCodec.java:99-147 (+ 91-94) at commit `1695f13` |
| `Recovery.recover` | `SegmentManager.recoverDataSegment` | SegmentManager.java:124-152 |

The other files hold proofs only: `ListLemmas.scala`, `ByteLemmas.scala`,
`RoundTripBeforeFix.scala`, `RoundTrip.scala`, `Integrity.scala`, and the lemmas in
`Recovery.scala`.

## Running it

```
# Stainless 0.10.4, stainless-dotty-standalone-0.10.4-linux.zip from the GitHub release
# (SHA-256 54a26e2b09173e48a46c29e44fb22c2f47d5f2e4c793ceba577052e98e0ed1a8);
# it bundles Z3 5.0.0 and cvc5 1.3.4. Java 21.
export STAINLESS=/path/to/stainless-0.10.4/stainless
./run.sh first-run     # the model before the fix, no proofs
./run.sh before-fix    # the model before the fix + P1
./run.sh fixed         # the fixed model (P2) + P1, P3, P4
```

`run.sh` passes `--no-colors --vc-cache=false --solvers=smt-z3,smt-cvc5 --timeout=120`
and sets `LC_ALL=C.UTF-8` (the Scala front end crashes under the POSIX locale).
`smt-z3,smt-cvc5` means Z3 and cvc5 over SMT-LIB, run as a portfolio; it is also what the
release's own `stainless.conf` selects. The default `nativez3` binding timed out on
trivial inductive steps of this model.

Results on 17 Nov 2026 (cloud container, 4 vCPUs; times vary between runs):

| Command | VCs | Valid | Invalid | Unknown | Solver time | Wall time |
|---|---|---|---|---|---|---|
| `./run.sh first-run` | 101 | 97 | 4 | 0 | 80 s | 1 min 29 s |
| `./run.sh before-fix` | 249 | 245 | 4 | 0 | 102 s | 1 min 54 s |
| `./run.sh fixed` | 408 | 408 | 0 | 0 | 54 s | 1 min 09 s |

VCs per file in `./run.sh fixed`: Bytes 71, Format 7, Codec 25, ListLemmas 100,
ByteLemmas 10, RoundTrip 38, Integrity 25, Recovery 131.

## How the overflow was found, and what Stainless did

The overflow had been found and reproduced in the Java before this model was written.
With `KEY_LEN = Integer.MAX_VALUE` and `VAL_LEN = 1`, `kLen + vLen` wraps negative in
`int`, so the "enough bytes left?" check passes. `decode()` then throws
`OutOfMemoryError` and `readRecordAt()` throws `IllegalArgumentException("capacity < 0")`,
and `KVStore.open` fails.

`first-run.txt` is the raw output of `./run.sh first-run` on the faithful model
(`CodecBeforeFix.scala`, unchanged since). **Stainless flagged the overflow by itself**:
4 of 101 VCs are invalid, each with a concrete 22-byte counterexample:

- the two additions in `HEADER_SIZE + kLen + vLen` (RecordCodec.java:127 before the fix),
  e.g. KEY_LEN bytes `7F FF FF F0`;
- the addition in `allocate(kLen + vLen)` (l.132);
- the read of the payload with that wrapped, negative length: the model's version of
  `IllegalArgumentException("capacity < 0")`.

For transparency: earlier drafts of the same model did not produce this output. One had
an import clash. One lacked size postconditions on the byte readers and timed out. One
used `BigInt` lengths, and there every overflow VC timed out (at 30 s, 120 s and 240 s)
without a counterexample. Those outputs were not kept. `first-run.txt` is the first run
of the model in its final form.

**The fix** (`RecordCodec.java`, its own commit): `readRecordAt` computes
`long total = (long) HEADER_SIZE + kLen + vLen`, keeps the existing EOF check (a record
that runs past EOF is still a torn tail), then rejects `total > Integer.MAX_VALUE` as
corruption. `decode()` compares `buf.remaining()` with `(long) kLen + vLen`. The file
format is unchanged. **Both parts are needed:** with the long arithmetic but no bound,
Stainless cannot prove 3 of `Codec.decode`'s VCs (`kLen + vLen`, the payload read, and
`(int) total`). They fail only on inputs over 2 GiB, which the solver cannot build, so
they time out rather than produce a counterexample.
`LengthOverflowTest.readRecordAtLengthAboveIntRange` shows that path in Java with a
sparse file over 2 GiB: `OutOfMemoryError` before the fix, `Corrupted(isTornTail = false)`
after.

Regression tests (`src/test/java/io/tinykv/LengthOverflowTest.java`) fail with the fix
reverted (IAE "capacity < 0" from `readRecordAt` and `KVStore.open`; `OutOfMemoryError`
from `decode` and from the >2 GiB case) and pass with it. `./gradlew test` is green.

## Sanity check: the proofs are not vacuous

If the assumptions were contradictory, Stainless would prove anything. Five false
variants of the statements were each checked and **none verified** (each came back
invalid or unknown):

- P4 claiming that all n records are recovered;
- P4 claiming that the valid length is `m`;
- P1 claiming `n == |encode(r)| + 1`;
- P3 with key and value swapped in `crc`;
- `tornPrefix` claiming `Corrupted(false)`.

## Step 5: a damaged length in the middle of the tail segment

**Question.** If a length field in the middle of the tail segment is damaged so that it
points past EOF, does recovery treat it as a torn tail and truncate away later, fully
written records? **Yes.**

Byte-level example (`TornTailClassificationTest`): three records `put("a","1")`,
`put("b","2")`, `put("c","3")`, each `22 + 1 + 1 = 24` bytes:

```
offset  0..23   record a
offset 24..47   record b   KEY_LEN at offsets 38..41 = 00 00 00 01
offset 48..71   record c
```

Changing the byte at offset 40 from `00` to `01` makes record b's KEY_LEN
`0x00000101 = 257`. At offset 24, 48 bytes remain but the header declares
`22 + 257 + 1 = 280`. `readRecordAt` returns `Corrupted(isTornTail = true)` ("Incomplete
payload at EOF"), and `recoverDataSegment` truncates the file to 24 bytes. Record c was
written in full and never touched, yet it is deleted from disk. The store opens normally,
with a WARN in the log.

The test pins this current behaviour; nothing was changed, because how to classify this
case is a design decision. P4 does not contradict it: P4's fault model only removes bytes
from the end and never changes written ones. A related case: a CRC mismatch on a record
that ends exactly at EOF is also reported as a torn tail (RecordCodec.java:149).

## Side finding (not fixed)

`RecordCodec.readHintAt` has the same int overflow: it checks
`size - position < HINT_HEADER_SIZE + kLen` in `int`. A hint file with
`KEY_LEN = Integer.MAX_VALUE` reaches `ByteBuffer.allocate(Integer.MAX_VALUE)` and throws
`OutOfMemoryError` (checked with a throwaway test). `SegmentManager.initialize` catches
only `Exception` around hint loading, so this would also stop the store from opening.
Hint files are not modelled here.
