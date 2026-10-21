# clove — single-file Clojure in C

Goal: a babashka-coverage-ish Clojure interpreter in one embeddable C file
(`clove.c`), inspired by Janet. Build: `make`. Tests: `make test`
(suite + conformance); `make test-full` adds the `CLOVE_GC_STRESS=1` pass.

This file is the live plan: status, invariants, and open threads. The
complete milestone log (roadmap items 1–50-odd, the July/August arc
summaries) is in [HISTORY.md](HISTORY.md); user-facing changes per release
are in [CHANGELOG.md](CHANGELOG.md); the feature surface is in the README.

## Status (as of 2026-10-14, unreleased on top of v0.5.1)

~17,000 lines, zero warnings, ~1920 assertions in `tests.clj` + ~500-expression
conformance corpus diffed against a JVM golden (`make conformance`), ASan/UBSan clean
(ASan needs `ASAN_OPTIONS=detect_leaks=0:detect_stack_use_after_return=0` —
required by conservative stack scanning, same as Boehm GC). CI: Linux (+ASan at
-O1, normal and GC-stress), macOS arm64 + x86_64/Rosetta, Windows (MSYS2
mingw64) — suite runs normal + GC-stress on every push; a nightly job runs the
differential fuzzer against babashka (`make fuzz`); tagged releases publish
portable binaries. Suite verdict is the exit status + the "tests complete: N
passed, 0 failed" line, never a grep for "error".

Renamed cljc → clove (2026-10-20): binary, source file, env vars, share dir,
`clove/` helpers and the C API all moved; `:cljc` stays a reader-conditional
alias and `~/.cljc_history` is migrated on first REPL start. HISTORY.md and
released CHANGELOG entries keep the old name. The GitHub repo is now
alexalemi/clove, and the checkout lives at ~/projects/clove.

Also (2026-10-20): `? question` in the REPL asks Claude
(`claude.clj` battery; C side keeps a 32 KB session transcript in
`repl_log`, dispatches `? ` lines, and exposes `clove/repl-edit*` (line editor
with preloaded text) + `clove/repl-log*`; `clove/sh-lines*` streams the SSE
response). Tools: read-only `clove_lookup` runs automatically; `propose_form`
only pre-fills the prompt — nothing Claude writes runs without the user's
Enter. Follow-ups: Windows key handling (`%VAR%` or a curl `-K` file), capture
`println` output of user forms in the transcript (only results/errors are
logged now), multi-line proposals (the editor is single-line).

Before that (2026-10-14): the silent-divergence round. `+ - * inc dec`
throw "long overflow" instead of wrapping (`unchecked-*` wrap); `catch` is
typed (see "Exception classes" in clove.c); `print`/`println` carry `N`/`M` and
`str` of a collection prints its contents readably; `fuzz/gen_expr.py` +
`fuzz/fuzz.sh` put the random-expression fuzzer in the repo, and its first
runs found six more divergences (subvec bounds, Ratio.doubleValue rounding,
empty-pattern replace, long range, abs of MIN_VALUE, 32-bit parseInt), all
fixed. Before that: minijax bring-up (74/74) and BigDecimal as a C numeric
type.

### GC invariants (read before touching allocation paths!)
1. Only `cell_alloc`/`env_alloc` can trigger collection (`xmalloc` cannot).
2. A cell's union is zeroed at allocation — half-built cells are always safe
   to mark/sweep.
3. Collections under construction must grow `len`/`n` incrementally when
   allocation can happen between slot fills (see eval VECTOR/MAP, qq_expand,
   SYM_RECUR). Values held only in C locals are safe (conservative scan).
4. Swept cells get tag `CLOVE_FREE`; mark refuses to traverse them.
5. `gc_scan_range` is exempt from ASan instrumentation by design.

## Open threads

Things the history log leaves unresolved, roughly by value:

- **meander `m/rewrite`** — blocked on syntax-quote: JVM qualifies an
  unresolvable bare symbol to the current ns (`with` →
  `meander.syntax.epsilon/with`); clove leaves it bare, so meander's parser
  registry misses. Fix is to qualify inside library loads, held back until a
  prelude/battery audit confirms nothing relies on clove's bare-symbol
  (non-hygienic) templates. Details: HISTORY.md, specter/meander entries.
- **BigInt contagion.** In-range BigInts are longs (`1N` reads as `1`; a
  bigint result that fits demotes), so `(reduce * 1N (range 1 30))` now
  throws "long overflow" — loud, where it used to wrap silently, but Clojure
  keeps going in BigInt. The real fix is a non-demoting BIGINT, which touches
  every `->tag == CLOVE_INT` integer check (`as_int` is the main choke point),
  `=`/`hash` across the two representations, and `int?` vs `integer?`.
  `fuzz.sh` strips the `N` suffix before diffing so this stays quiet there.
- **Untyped interpreter errors.** Only the error sites with an obvious JVM
  class go through `clove_error_cls` (arithmetic, index bounds, number
  parsing, nil/non-number arguments, stack overflow). Everything else is
  untyped and matches any `catch` clause but ExceptionInfo/AssertionError/
  Error. Classify more sites as ported code turns out to dispatch on them —
  wrong-arity (ArityException) and "not a function" (ClassCastException) are
  the likely next ones. `type` of an exception is still its representation
  (`:map`/`:string`); only `class` and `instance?` see the recorded class.
- **Float division by an exact zero** is `##Inf` (as compiled Clojure with
  primitive doubles); boxed JVM math and babashka throw. Typing-dependent on
  the JVM itself, so there is no single right answer — left as is.
- **BigDecimal leftovers**: `(BigDecimal. 0.1)` uses the double's shortest
  repr (Java's `valueOf`) rather than the exact binary expansion; only
  HALF_UP rounding; no `*math-context*`; `(bigint 2.7M)` demotes to a long
  like every in-range integer.
- **Libraries needing a JVM reimplementation**: `clojure.data.xml` (StAX),
  `cognitect.transit` (see README "Not yet").
- **Performance**: ~1.3–6x of babashka; remaining levers are in HISTORY.md
  item 36 and the July perf-campaign summary.

## Prior art / inspiration

- **jolt** (<https://jolt-lang.net/>, <https://github.com/jolt-lang/jolt>,
  EPL-1.0 like clove) — a self-hosted Clojure that compiles to Scheme
  (Chez natively; Gambit → JavaScript) instead of the JVM. Same "Clojure
  without a JVM" target as clove, opposite strategy: clove is a tree-walking
  interpreter in one C file that BUNDLES source next to the runtime, jolt
  is a real compiler emitting host-neutral IR → Scheme, AoT-linked into a
  standalone binary with C deps statically linked. Worth stealing from:
  - `jolt build -m <ns>` — entrypoint-based standalone binaries; the
    direct model for roadmap item 48 (HISTORY.md).
  - `jolt run -m` with dependency resolution — clove has `vendor` +
    `*load-path*`, no resolver.
  - Native C FFI and OS-threaded `future`/`agent`/`pmap` as headline
    goals (clove's concurrency is fiber/coroutine-based — HISTORY.md item 46 — so
    it is cooperative, not parallel; jolt is the pressure test for
    whether that ceiling matters).
  Non-goals for clove: we are not becoming a compiler or growing a second
  host language. The single embeddable `clove.c` file is the constraint.

## Known divergences from Clojure (deliberate)
- `catch` is typed only as far as exception classes are recorded; hash
  maps/sets iterate in hash order; in-range BigInts are longs (see README's
  list — it supersedes this one)
- Namespaces: flat global + per-ns aliases behind a symbol-based namespace
  layer; top-level `user` defs are bare, hence visible from other
  namespaces; no remove-ns/ns-unmap (root Bindings are append-only)
- Quasiquote: auto-gensym `x#` works; nested quasiquote levels do not
- Conservative GC may retain garbage pointed at by stale stack slots

## Testing conventions
- `tests.clj` — every bug fix gets a regression assertion (assert= prints
  PASS/FAIL and counts; the file exits 1 if any failed, and script mode
  aborts non-zero on the first uncaught error)
- Verify sweeps: `make test-full`, then an ASan build at **-O1** (at -O0
  the stack keeps values alive that -O1 drops, hiding missing GC roots):
  `cc -g -O1 -fsanitize=address,undefined -o clove clove.c -lm -ldl`
  with the ASAN_OPTIONS above, both normal and stress. Build it AS `./clove`:
  the suite's subprocess tests re-invoke `<exe-dir>/clove`, and under any other
  name they fail with "not found" (CI's ASan job did exactly that, unnoticed,
  while its `! grep` check could not fail a `set -e` step).
- Behavior that has a JVM answer goes in `fuzz/conformance.txt` too;
  regenerate the golden with
  `bb -f fuzz/runner.clj -- fuzz/conformance.txt > fuzz/conformance_expected.txt`.
- `make fuzz` (differential vs babashka) before a release and after touching
  numerics, printing, or exceptions; a finding becomes a conformance line.
- Memory bound check: 2M-iteration churn loop should stay ~5 MB RSS.
