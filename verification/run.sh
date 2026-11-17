#!/usr/bin/env bash
# Runs Stainless 0.10.4 on the record-codec model. See README.md.
#
#   ./run.sh first-run    the model of the code before the fix, without any proofs:
#                         the run saved in first-run.txt (expected: 4 invalid VCs)
#   ./run.sh before-fix   the same model plus the proof of P1 (round trip) for it
#                         (expected: the same 4 invalid VCs in decode, everything else valid)
#   ./run.sh fixed        the model of the fixed code (P2: all its VCs valid) and the
#                         proofs about it (expected: every VC valid)
#
# Set STAINLESS to the `stainless` launcher of the stainless-dotty-standalone-0.10.4
# release if it is not on PATH.
set -euo pipefail
cd "$(dirname "$0")"

STAINLESS="${STAINLESS:-stainless}"
# The Scala 3 front end crashes on some class-file names under the POSIX locale.
export LC_ALL=C.UTF-8

# smt-z3 / smt-cvc5: Z3 and cvc5 driven through SMT-LIB, both bundled with the release
# (they are also what the release's own stainless.conf selects). Stainless asks both and
# takes the first answer. The default "nativez3" binding timed out on trivial inductive
# steps with this model.
OPTS=(--no-colors --vc-cache=false --solvers=smt-z3,smt-cvc5 --timeout=120)

MODEL=(Crc.scala Bytes.scala Format.scala)
LEMMAS=(ListLemmas.scala ByteLemmas.scala)

case "${1:-}" in
  first-run)  files=("${MODEL[@]}" CodecBeforeFix.scala) ;;
  before-fix) files=("${MODEL[@]}" CodecBeforeFix.scala "${LEMMAS[@]}" RoundTripBeforeFix.scala) ;;
  fixed)      files=("${MODEL[@]}" Codec.scala "${LEMMAS[@]}" RoundTrip.scala) ;;
  *) echo "usage: $0 first-run|before-fix|fixed" >&2; exit 2 ;;
esac

# JAVA_TOOL_OPTIONS is unset only so that machine-specific JVM settings are not echoed
# into the output; Stainless needs no network access.
exec env -u JAVA_TOOL_OPTIONS "$STAINLESS" "${OPTS[@]}" "${files[@]}"
