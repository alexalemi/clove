# clove — single-file Clojure in C

Goal: a babashka-coverage-ish Clojure interpreter in one embeddable C file
(`clove.c`), inspired by Janet. Build: `make`. Tests: `make test`
(suite + conformance); `make test-full` adds the `CLOVE_GC_STRESS=1` pass.

This file is the live plan: status, invariants, and open threads. The
complete milestone log (roadmap items 1–50-odd, the July/August arc
summaries) is in [HISTORY.md](HISTORY.md); user-facing changes per release
are in [CHANGELOG.md](CHANGELOG.md); the feature surface is in the README.

## Status (as of 2026-11-02, v0.9.0)

~20,600 lines, zero warnings, ~2200 assertions in `tests.clj` + ~540-expression
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

Everyday sweep (2026-11-01): a 559-expression corpus of everyday idioms
diffed against bb turned up silently wrong results (`spit :append`, atom
`:validator`, multi-arg `sh`, `*command-line-args*`, reversed small-map order,
`with-in-str`+`read-line`, `.getBytes`, `make-array` zeros, exception `str`)
and ~60 missing names. Fixed in one pass: most of the gap-fill is Clojure in
the last prelude chunk (`/* ── everyday gap-fill`), with C support for
ordered maps (`map_assoc`/`map_compact`: small = array map, big = HAMT →
entry in an `order` vector with tombstones), atom validators, `clove/proc*`
(fork/execvp; popen fallback on Windows), `clove/file-op*`, regex flags
(`RXF_*` in `rx_dotall`), `format` flags, and `*math-context*`
(`bd_mc_precision`). Not covered: other java.time types, cl-format `~<~>`.
Pre-existing, unrelated: minijax notebook-test has 8 failures (`:=` define
cells), identical on v0.7.0.

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

- **Docs site** (`doc/`, built by `./clove doc/build.clj` into `_site/`).
  Live at alexalemi.com/clove (alexalemi.github.io/clove redirects), deployed
  by `.github/workflows/pages.yml` on every push to main; the workflow fails
  if the wasm interpreter, the cheat sheet PDF or `llms.txt` is missing.
  Still to do: self-hosted fonts, search beyond the reference filter.
  `doc/cheatsheet.edn` is checked by tests.clj; the build lists documented
  names it leaves out. `llms.txt` (repo root, copied to the site) is the
  agent-facing summary: keep its claims in step with the README.
- **Startup**: ~8 ms (`benchmarks/01-startup.clj`, 2026-11-02; bb 7 ms,
  v0.7.0 8 ms). The 0.8.0 regression to ~10 ms was linear root-binding
  lookups and a 1,024-bucket intern table; both fixed. What's left is
  reading and evaluating the prelude: ~87k cells (~4.9 MB, ~1,200 page
  faults) and ~1,300 top-level forms. Measure with hyperfine and put a
  warm-up run first: the first command in a hyperfine run reads ~4 ms slow.
  Prefaulting cell blocks with MAP_POPULATE saved only ~0.2 ms.
- **`clojure.data.priority-map`**: `conj`/`into` on one bypass its deftype
  `cons` (they treat it as a plain map), and it prints its internals.
  `assoc`/`peek`/`pop` work.
- **Small REPL rough edges** seen while writing docs: `printn` suggests
  `print` rather than `println`; `defn` returns the fn rather than the var
  (`#'user/f` on the JVM).
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
- **Data readers**: only `#inst`/`#uuid`; `*data-readers*` and
  `data_readers.clj` are not consulted, so other tags are read errors (inside
  a skipped `#?` branch they are tolerated).
- **Method override timing**: an instance method beats a global `.method`
  shim only for names in `method_notes`, filled when a deftype/reify/proxy
  form is macroexpanded or registered. Code the VM compiled before then
  still calls the shim directly.
- **Float division by an exact zero** is `##Inf` (as compiled Clojure with
  primitive doubles); boxed JVM math and babashka throw. Typing-dependent on
  the JVM itself, so there is no single right answer — left as is.
- **BigDecimal leftovers**: `(BigDecimal. 0.1)` uses the double's shortest
  repr (Java's `valueOf`) rather than the exact binary expansion; only
  HALF_UP rounding (`with-precision` with another `:rounding` mode throws
  rather than rounding differently).
- **Libraries needing a JVM reimplementation**: `clojure.data.xml` (StAX),
  `cognitect.transit` (see README "Not yet").
- **Standalone notebooks in wasm** (`clove notebook -S`, clerk.clj). clove
  compiles under emscripten with `-DCLOVE_NO_MAIN`; the `__EMSCRIPTEN__`
  guards in clove.c drop coroutines (no ucontext), take the stack floor from
  `emscripten_stack_get_end`, and export `clove_wasm_eval`. The GC needs
  ASYNCIFY: wasm locals are invisible to the conservative scan, so
  `gc_collect` calls `emscripten_scan_registers`, which unwinds the stack into
  a heap buffer and resumes from `setTimeout`. Every GC therefore makes an eval
  asynchronous, and the page queues its calls. The 1.5 MB shadow stack is
  sized so clove's guard trips (~1000 frames) before a browser's ~1 MB engine
  stack, whose overflow kills the instance (the page then asks for a reload).
  Open: port coroutines to emscripten fibers (also ASYNCIFY-based); try
  Binaryen `--spill-pointers` with a current emsdk instead of ASYNCIFY
  (synchronous, likely faster, and would lift the depth cap). Tested only
  against Debian's emcc 3.1.6, in node and Chrome.
- **Persistent map update loops are ~4x bb** (`(reduce #(update %1 k f) {} ..)`,
  `assoc-in`/`update-in` at 1M steps; 2026-11-02). Builders are within 2x
  (2026-11-03, CPU time vs bb: `frequencies` 1.4x, `group-by` 1.1x,
  `(into #{} ..)` 1.0x, `(into {} ..)` 1.5x, a dissoc/assoc loop on a 100k
  map 1.7x). `frequencies`/`group-by` are natives; `into` is still a defn,
  so its `from` local pins a lazy input whole while it builds.
  Big maps keep an insertion stamp in each HAMT entry cell (iteration sorts
  by stamp), so a value update copies only the trie path; dense levels are
  32-slot array nodes. What is left per
  persistent assoc: ~3 cells plus ~2.6 kid arrays of up to 64 slots copied,
  and the GC/page-fault cost of that churn; the interpreter's call overhead
  is most of the rest. Levers: the fast-call path in `apply` handles only
  single fixed-arity fns (multi-arity `update`/`fnil` take the slow path);
  cell allocation and sweep cost.
- **Heap retention**: the sweep frees empty cell blocks beyond the next
  threshold, empty blocks the last cycle never reached, and empty env blocks
  beyond 3x live envs; `(gc)` keeps max(2x live, 1M cells). The churn floor
  is bounded by 16x live, so retention tracks the live set. A program that
  blocks (sleep, scheduler wait, stdin/socket read, `sh`, REPL/nREPL input)
  gets the same releasing collection from `gc_idle` when it allocated since
  memory was last given back, at most once a second. What remains:
  (1) false retention from the conservative scan is mostly gone: the big
  special forms live in noinline helpers (`eval_try`, `eval_loop`, ...), so
  `eval_inner`'s frame is ~150 bytes and holds no arrays; `apply` zeroes its
  stack env; `vm_compile` and `lazy_force` scrub the stack below them;
  `lazy_force` forwards each realized step to the end of the chain, so one
  stale word on an early lazy cell no longer pins the whole tail; `apply`
  calls natives with no frame of its own in between (clang's `apply` frame
  held the iterate head under `(first (filter ..))` in a slot it never
  wrote); `eval_body` and `let` zero the stack the last form dirtied
  (`stack_scrub_dirty`: eval/apply_fn/gc_drain note the deepest address,
  plus a 1 KB margin for natives), so a frame's unwritten slot no longer
  shows the previous form's values (the `loop` vector under -O0 and
  mingw). Compiled bodies (vm_run) don't scrub between statements, and
  which dead words survive there is still up to the compiler; only
  precise roots would close this for good; (2) Binding
  structs and the malloc arena (`malloc_trim` only on glibc) are not
  returned. `clove/heap-bytes*` measures (1) without RSS
  noise.
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
  sets iterate in hash order, maps in insertion order at every size (see
  README's list — it supersedes this one)
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
