# clove — single-file Clojure in C

Goal: a babashka-coverage-ish Clojure interpreter in one embeddable C file
(`clove.c`), inspired by Janet. Build: `make`. Tests: `make test`
(suite + conformance); `make test-full` adds the `CLOVE_GC_STRESS=1` pass.

This file is the live plan: status, invariants, and open threads. The
complete milestone log (roadmap items 1–50-odd, the July/August arc
summaries) is in [HISTORY.md](HISTORY.md); user-facing changes per release
are in [CHANGELOG.md](CHANGELOG.md); the feature surface is in the README.

## Status (as of 2026-10-31, v0.7.0)

~18,000 lines, zero warnings, ~2040 assertions in `tests.clj` + ~510-expression
conformance corpus diffed against a JVM golden (`make conformance`), ASan/UBSan clean
(ASan needs `ASAN_OPTIONS=detect_leaks=0:detect_stack_use_after_return=0` —
required by conservative stack scanning, same as Boehm GC). CI: Linux (+ASan at
-O1, normal and GC-stress), macOS arm64 + x86_64/Rosetta, Windows (MSYS2
mingw64) — suite runs normal + GC-stress on every push; a nightly job runs the
differential fuzzer against babashka (`make fuzz`); tagged releases publish
portable binaries. Suite verdict is the exit status + the "tests complete: N
passed, 0 failed" line, never a grep for "error".

BigInt contagion (2026-10-21): CLOVE_BIGINT is now the BigInt *type* at any
magnitude (1N, BigInt arithmetic, whole ratio results, `bigint`), not "an
int too big for int64". The `big_*` helpers still return the compact form
(ratio/BigDecimal internals and `+'` overflow want that); user-visible BigInt
results go through `to_bigint`. Integer positions read values through
`int_value` (INT or an in-range BIGINT), `clove_eq`/`clove_hash` equate 1 and
1N, and `make_ratio`/`mk_bigdec` demote their parts. Diffed against bb
(conformance lines + `fuzz.sh` no longer strips the `N`); minijax unchanged.

Fixed-buffer audit (2026-10-21): every `char buf[N]` that could cut a value
users see now grows (err_msg is heap-grown via err_msg_vset, `intern_qual`
builds ns/name, `sb_printf` is unbounded); display-only buffers (prompt,
paths for hints, the capped error trace, val_desc's %.60s) stay fixed on
purpose. %f of doubles goes through the BigDecimal path (shortest digits,
HALF_UP) and `fmt_pad_num` applies flags/width. fuzz/gen_expr.py now emits
long names (`long_name()`).

Joy round (2026-10-21): the line editor (`/* ───── Line editor` in clove.c)
is a whole-frame-redraw multi-line editor: `Ed` state, `ed_render` computes
rows from term_width, `ed_key` decodes escapes (lone Esc = 25–40 ms quiet),
vi normal mode in `ed_normal_cmd` (+ `.` via a key replay queue), arglist
hints via `ed_arglists` (doc-info under its own ErrFrame, cached), history v2
(escaped newlines). `tests/pty_repl.py` (`make test-pty`, needs pexpect)
drives it. Also: `inspect.clj` (pure render/step + a raw-mode loop),
test.clj path diffs (`clove/diff-paths`), `tasks.clj` (`clove run/tasks/new`,
clove.edn), bare `?` after an error. Not done: vi visual mode, editor on
Windows untested by hand (compiles; VT input mode).

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
logged now), multi-line proposals (the editor is multi-line now; propose_form still sends one line).

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
- **BigInt leftovers.** No separate BigInteger type (`biginteger` prints
  `3N`); a ratio's `numerator`/`denominator` are longs when they fit (JVM:
  BigInteger). Natives that take an integer through `int_value`/`as_int`
  accept a small BigInt where the JVM is stricter (`bit-and`, `format "%d"`).
  A new `tag == CLOVE_INT` check meaning "is an integer" should use
  `int_value` instead, or 1N is silently rejected.
- **Untyped interpreter errors.** Only the error sites with an obvious JVM
  class go through `clove_error_cls` (arithmetic, index bounds, number
  parsing, nil/non-number arguments, stack overflow). Everything else is
  untyped and matches any `catch` clause but ExceptionInfo/AssertionError/
  Error. Classify more sites as ported code turns out to dispatch on them —
  wrong-arity (ArityException) and "not a function" (ClassCastException) are
  the likely next ones. `type` of an exception is still its representation
  (`:map`/`:string`); only `class` and `instance?` see the recorded class.
- **Var metadata.** `def`/`defn` drop name metadata (`^{:test f}`, `^:foo`),
  and `alter-meta!` on a var is a no-op; `(meta #'x)` is synthesized from the
  doc registry (`:name`/`:doc`/`:arglists`). So `test` on a var finds no
  `:test`, and clojure.test-style `:test` metadata doesn't round-trip.
- **Remaining inert stubs** (found 2026-10-31 by a differential sweep against
  bb): `munge`/`namespace-munge` don't mangle, `bytes?`/`chunked-seq?` are
  always false, `supers` is `#{}`, `bean` is `{}`. A global `.method` host shim
  (e.g. `.write`) takes precedence over a `reify`/`proxy` method of the same
  name. Data readers: only `#inst`/`#uuid`, with no `*data-readers*` lookup.
- **Float division by an exact zero** is `##Inf` (as compiled Clojure with
  primitive doubles); boxed JVM math and babashka throw. Typing-dependent on
  the JVM itself, so there is no single right answer — left as is.
- **BigDecimal leftovers**: `(BigDecimal. 0.1)` uses the double's shortest
  repr (Java's `valueOf`) rather than the exact binary expansion; only
  HALF_UP rounding; no `*math-context*`.
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
  maps/sets iterate in hash order (see README's list — it supersedes this one)
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
- `make test-pty` after touching the line editor (pexpect; ASan builds pass
  too, timing is loose enough).
- Memory bound check: 2M-iteration churn loop should stay ~5 MB RSS.
