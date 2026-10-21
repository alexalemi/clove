# Differential + invariant fuzzing, conformance corpus

## Quick start

```sh
make fuzz                 # random seed, 2000 expressions; needs bb + python3
make fuzz SEED=7 N=5000   # reproduce / go deeper
```

`fuzz.sh` runs three corpora from one seed and exits non-zero on any finding:
random typed expressions (`gen_expr.py`) and BMP string/regex ops
(`gen_str.py bmp`), both of which must print exactly what babashka prints,
and string invariants (`gen_str.py inv`), which must all print `true` with
and without GC stress. The nightly `Fuzz` workflow runs it with fresh seeds
and prints them, so a red night reproduces locally.

A finding is one of two things. A bug: fix it, add the expression to
`conformance.txt`, regenerate the golden. Or a deliberate divergence: teach
the generator to steer around it and say why in a comment there, so the
fuzzer stays quiet about what is already known.

## Expression fuzzing

`gen_expr.py SEED N` generates typed expressions over ints, exact and
floating numbers, booleans, strings, int vectors, sets and keyword maps --
about 150 productions of core, string, set and seq functions, including the
ones that throw (the runners print `:FUZZERR` for a throw, so "both throw" is
a match and "one throws" is a finding). Its first runs found `subvec`
accepting out-of-range bounds, ratio-to-double conversion rounding
differently from `Ratio.doubleValue`, `(str/replace s "" r)` erroring,
`(long 1e19)` not throwing, `(abs Long/MIN_VALUE)`, and a 64-bit
`Integer/parseInt` -- all fixed, all now in the conformance corpus.

What it deliberately steers around (see the comments in the generator):
- hash order: sets and maps only reach the printer through sorted views
- in-range BigInts print without `N` in clove (they demote to longs);
  `fuzz.sh` strips the suffix from both sides before diffing
- float division by an exact zero: clove gives `##Inf`, as compiled Clojure
  does with primitive doubles; boxed JVM math and bb throw
- `Long/MIN_VALUE` as an operand: boxed JVM math computes `(- x y)` as
  `(+ x (- y))` and overflows where the primitive path and clove do not
- exception messages: only the class of a throw is compared

## String/regex fuzzing

String/regex-focused fuzzer used to validate the codepoint-strings work.

```sh
python3 gen_str.py 42 400 bmp > bmp.txt     # BMP corpus: clove must match bb exactly
python3 gen_str.py 43 400 inv > inv.txt     # invariants: every line must print true

clove runner_clove.clj bmp.txt > a.txt
bb -f runner.clj -- bmp.txt > b.txt
diff a.txt b.txt                             # empty = no divergences

clove runner_clove.clj inv.txt | grep -v '^true$'          # empty = all hold
CLOVE_GC_STRESS=1 clove runner_clove.clj inv.txt | grep -v '^true$'
```

The BMP corpus stays below U+10000 because JVM strings index UTF-16 code
units — astral chars legitimately count as 2 there and 1 in clove — so
astral inputs are exercised by the self-consistency invariants instead.

## Conformance corpus

`conformance.txt` is a curated corpus of clojure.core expressions whose
printed value must match JVM Clojure exactly — the same eval-and-`prn`
runners, but hand-written coverage of the core vocabulary (numbers incl.
ratios/bignums/double printing, strings/regex, collections, seq library,
destructuring, transducers, multimethods, error handling) rather than
generated strings. `conformance_expected.txt` is the golden output; every
line was verified against JVM Clojure semantics. `make conformance` (also
part of `make test`) diffs clove against the golden, and re-derives the
golden live when `bb` is on PATH:

```sh
clove runner_clove.clj conformance.txt | diff - conformance_expected.txt
bb -f runner.clj -- conformance.txt > conformance_expected.txt   # regenerate golden
```

Ground rules for corpus lines: one deterministic expression per line
(`;` comments and blanks are skipped), print unordered collections only
through sorted views, and avoid the documented divergences (astral strings,
small-`N` literal demotion, hash values, gensym). The corpus has already earned its keep: it caught
`(partition-all n step coll)`, 3-coll `interleave`, `.toUpperCase`,
`rationalize`, and double-`range` element promotion all diverging — each
now fixed with regression tests.
