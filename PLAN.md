# cljc — single-file Clojure in C

Goal: a babashka-coverage-ish Clojure interpreter in one embeddable C file
(`cljc.c`), inspired by Janet. Build: `make`. Tests: `make test`
(suite + conformance); `make test-full` adds the `CLJC_GC_STRESS=1` pass.

This file is the live plan: status, invariants, and open threads. The
complete milestone log (roadmap items 1–50-odd, the July/August arc
summaries) is in [HISTORY.md](HISTORY.md); user-facing changes per release
are in [CHANGELOG.md](CHANGELOG.md); the feature surface is in the README.

## Status (as of 2026-10-04, unreleased on top of v0.5.1)

~16,500 lines, zero warnings, ~1680 assertions in `tests.clj` + 462-expression
conformance corpus diffed against a JVM golden (`make conformance`), ASan/UBSan clean
(ASan needs `ASAN_OPTIONS=detect_leaks=0:detect_stack_use_after_return=0` —
required by conservative stack scanning, same as Boehm GC). CI: Linux (+ASan),
macOS arm64 + x86_64/Rosetta, Windows (MSYS2 mingw64) — suite runs normal +
GC-stress on every push; tagged releases publish portable binaries.

Most recent work: minijax bring-up (74/74), then BigDecimal promoted from a
prelude tagged map to a C numeric type (CLJC_BIGDEC) — exact arithmetic with
Java's scale rules, `1.5M` literals, value `=`/hash, exact `%f` — fuzzed
identical to the JVM over 2000 cases x 25 ops.

### GC invariants (read before touching allocation paths!)
1. Only `cell_alloc`/`env_alloc` can trigger collection (`xmalloc` cannot).
2. A cell's union is zeroed at allocation — half-built cells are always safe
   to mark/sweep.
3. Collections under construction must grow `len`/`n` incrementally when
   allocation can happen between slot fills (see eval VECTOR/MAP, qq_expand,
   SYM_RECUR). Values held only in C locals are safe (conservative scan).
4. Swept cells get tag `CLJC_FREE`; mark refuses to traverse them.
5. `gc_scan_range` is exempt from ASan instrumentation by design.

## Open threads

Things the history log leaves unresolved, roughly by value:

- **meander `m/rewrite`** — blocked on syntax-quote: JVM qualifies an
  unresolvable bare symbol to the current ns (`with` →
  `meander.syntax.epsilon/with`); cljc leaves it bare, so meander's parser
  registry misses. Fix is to qualify inside library loads, held back until a
  prelude/battery audit confirms nothing relies on cljc's bare-symbol
  (non-hygienic) templates. Details: HISTORY.md, specter/meander entries.
- **`*` wraps on int64 overflow** where Clojure throws "long overflow".
  Silent wrong answers in ported numeric code; `*'` is the workaround. A
  checked `*` costs an overflow test on the hottest arithmetic path —
  measure before deciding.
- **`println`/`print` of bigints and decimals** omit the `N`/`M` suffix
  (Clojure prints `1N`, `1.5M`; only `str` drops it). The printer has a
  single `readably` flag, and `str` and `print` share the non-readable side.
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
  EPL-1.0 like cljc) — a self-hosted Clojure that compiles to Scheme
  (Chez natively; Gambit → JavaScript) instead of the JVM. Same "Clojure
  without a JVM" target as cljc, opposite strategy: cljc is a tree-walking
  interpreter in one C file that BUNDLES source next to the runtime, jolt
  is a real compiler emitting host-neutral IR → Scheme, AoT-linked into a
  standalone binary with C deps statically linked. Worth stealing from:
  - `jolt build -m <ns>` — entrypoint-based standalone binaries; the
    direct model for roadmap item 48 (HISTORY.md).
  - `jolt run -m` with dependency resolution — cljc has `vendor` +
    `*load-path*`, no resolver.
  - Native C FFI and OS-threaded `future`/`agent`/`pmap` as headline
    goals (cljc's concurrency is fiber/coroutine-based — HISTORY.md item 46 — so
    it is cooperative, not parallel; jolt is the pressure test for
    whether that ceiling matters).
  Non-goals for cljc: we are not becoming a compiler or growing a second
  host language. The single embeddable `cljc.c` file is the constraint.

## Known divergences from Clojure (deliberate)
- `catch` is untyped; hash maps/sets iterate in hash order; plain int64
  arithmetic wraps (see README's list — it supersedes this one)
- Namespaces: flat global + per-ns aliases behind a symbol-based namespace
  layer; top-level `user` defs are bare, hence visible from other
  namespaces; no remove-ns/ns-unmap (root Bindings are append-only)
- Quasiquote: auto-gensym `x#` works; nested quasiquote levels do not
- Conservative GC may retain garbage pointed at by stale stack slots

## Testing conventions
- `tests.clj` — every bug fix gets a regression assertion (assert= prints
  PASS/FAIL; script mode aborts on first `error:`)
- Verify sweeps: `make test-full`, then an ASan build at **-O1** (at -O0
  the stack keeps values alive that -O1 drops, hiding missing GC roots):
  `cc -g -O1 -fsanitize=address,undefined -o /tmp/cljc-asan cljc.c -lm -ldl`
  with the ASAN_OPTIONS above, both normal and stress.
- Behavior that has a JVM answer goes in `fuzz/conformance.txt` too;
  regenerate the golden with `bb -f fuzz/runner.clj -- fuzz/conformance.txt`.
- Memory bound check: 2M-iteration churn loop should stay ~5 MB RSS.
