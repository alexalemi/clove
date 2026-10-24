# Changelog

## Unreleased

### Renamed: cljc is now clove
The old name collided with the `.cljc` file extension. The binary is `clove`,
the source is `clove.c`, the share dir is `$PREFIX/share/clove`, and the
environment variables are `CLOVE_PATH`, `CLOVE_CC`, `CLOVE_GC_STRESS`, … The
REPL prompt is `clove[n]>`. `~/.cljc_history` moves to `~/.clove_history` the
first time the REPL starts. Internal helpers moved from `cljc/…` to `clove/…`,
and the C embedding API from `cljc_*`/`Cljc` to `clove_*`/`Clove`. The
reader-conditional feature is `:clove`; `:cljc` is still accepted, so existing
`#?(:cljc …)` code keeps working. `.cljc` files load as before.

### Changed — behavior that used to diverge silently from Clojure
- **Integer overflow throws.** `+`, `-`, `*`, `inc` and `dec` raise
  `ArithmeticException` "long overflow" when the result leaves the int64
  range, as Clojure does; they used to wrap, so `(* 4611686018427387904 4)`
  was `0`. `+'`/`-'`/`*'`/`inc'`/`dec'` still promote to a bigint, and
  `unchecked-add`/`-subtract`/`-multiply`/`-inc`/`-dec`/`-negate` are now the
  ones that wrap (they were aliases of the checked ops). Measured cost on the
  arithmetic benchmarks: 0–4% (fib, fib32 within noise; the tight `loop`/`recur` counter 4%). Code that relied on wrapping —
  hash mixers, LCGs — needs the `unchecked-` forms, as on the JVM.
- **`catch` is typed.** The first `catch` clause whose class the exception
  *is-a* handles it, and an exception no clause matches is rethrown (after
  `finally`); `catch` used to ignore the class, so the first clause took
  everything. Classes are recorded on `ex-info` (`ExceptionInfo`), on the
  exception constructors (`IllegalArgumentException.`, `IllegalStateException.`,
  `Exception.`, … plus new `Throwable.`, `Error.`, `ArithmeticException.`,
  `NumberFormatException.`, `IndexOutOfBoundsException.`,
  `NullPointerException.`, `ClassCastException.`, `InterruptedException.`), on
  `assert`/`:pre`/`:post` failures (`AssertionError` — an `Error`, so
  `(catch Exception e …)` no longer swallows a failed assertion), and on the
  interpreter errors with an obvious JVM class: division by zero and overflow
  (`ArithmeticException`), index out of bounds, number parsing, nil or a
  non-number where a number goes (`NullPointerException`/`ClassCastException`),
  `case`/`condp` fall-through and missing multimethods
  (`IllegalArgumentException`), stack overflow (`StackOverflowError`). Other
  interpreter errors stay untyped and match any clause except
  `ExceptionInfo`/`AssertionError`/`Error`. `class` and `instance?` report
  the recorded class; the class survives rethrow, `finally`, and futures.
  `(is (thrown? SomeClass …))` in the test battery checks the class too.
- **`Integer/parseInt`, `Long/parseLong`, `Double/parseDouble` throw
  `NumberFormatException`** on malformed input; they returned nil, so
  `(try (Integer/parseInt s) (catch NumberFormatException _ 0))` yielded nil.
  `Integer/parseInt` also rejects values outside the 32-bit range, and the
  radix arities accept a sign. `parse-long`/`parse-double` remain the
  nil-returning ones.
- **Printing bigints and decimals.** `print`/`println`/`print-str` emit the
  `N`/`M` suffix like `pr` does (`1.5M`, not `1.5`); `str` of a bare number
  still drops it. And `str` of a collection prints its contents readably:
  `(str ["a" 1.5M])` is `"[\"a\" 1.5M]"`, where it was `"[a 1.5]"`.
- **BigInt is a type, not a size.** `1N` is a BigInt (it read as the long
  `1`), and BigInt arithmetic stays BigInt whatever the magnitude, as
  Clojure's contagion does: `(+ 1N 1)` is `2N`, and
  `(reduce * 1N (range 1 30))` returns
  `8841761993739701954543616000000N` where it threw "long overflow". A
  whole result of ratio arithmetic is a BigInt too (`(+ 1/2 1/2)` is `1N`),
  while long division stays long (`(/ 4 2)` is `2`). `bigint` and
  `rationalize` of a double return BigInts; `(bigint 1e30)` is exact. Longs
  and BigInts are still one category: `(= 1 1N)`, equal hashes, the same
  map/set key, and a small BigInt works as an index (`(nth v 1N)`). Visible
  change: `pr-str` of these results now carries the `N`, and `int?` is false
  for them (`integer?` is true). `biginteger` returns the same type, so it
  prints `3N` where the JVM's `BigInteger` prints `3`.
- **Comparator results are read by sign.** A `sort`/`sort-by` comparator
  returning a BigInt, or a long beyond 32 bits (`(fn [a b] (- a b))` over
  large values), used to be truncated to a C `int`, which could flip or zero
  the ordering.

### Added
- **A multi-line REPL editor, with vi mode.** Enter runs a complete form and
  otherwise opens a new line indented for the open brackets; Alt-Enter /
  Ctrl-J always add a line; ↑/↓ move between lines and walk history past the
  first/last, and history keeps whole multi-line forms (`~/.clove_history`
  moves to an escaped v2 format, upgraded in place). Brackets and quotes
  auto-pair (typing a closer steps over it, Backspace removes an empty pair),
  pastes arrive verbatim (bracketed paste), `\(` is a char, not a bracket,
  and the arglists of the call around the cursor show dimly after the text
  (`(map inc▏)  [f] [f c] [f c1 c2] …`). vi mode — `CLOVE_EDIT_MODE=vi`,
  `set editing-mode vi` in `~/.inputrc`, or `(clove/edit-mode :vi)` — has
  insert/normal with cursor shapes, the usual motions (`hjkl w b e W B E 0 ^ $
  f F t T ; , % gg G`), `d`/`c`/`y` with counts, motions and text objects
  (`iw aw i( a( ib i[ i{ i" a"` …), `dd cc yy D C Y x X s S r ~ J p P o O`,
  `u`/`Ctrl-R`, and `.`. `make test-pty` drives it through a real pty.
- **A bare `?` right after an error** asks Claude to explain that error
  (the REPL hints `(? explains)` when a key is set).
- **`(inspect x)`** opens a full-screen data inspector (`inspect.clj`):
  breadcrumbs, `j`/`k`/PgUp/PgDn/`g`/`G` to move, Enter/`l` into a map entry,
  element, or atom's value, `h` back, `q` returns the value being viewed.
  Lazy and infinite seqs realize only what's on screen. Without a terminal it
  pprints.
- **Test failures show where values differ.** `(is (= expected actual))`
  evaluates each side once and prints `expected:`/`actual:` and a path diff
  (`[:roles 1]  expected :dev, got :ops`, at most 10 lines); the header names
  the test (`FAIL in (users)`). `clove/diff-paths` gives the diff as data.
- **Project tasks, like `bb tasks`.** `clove run <task>` runs a task from the
  nearest `clove.edn` (`{:paths [...] :tasks {test "clove test" ...}}`;
  strings are shell commands, lists are Clojure forms, maps take `:task`
  `:doc` `:depends` `:interactive`), with dependencies run once each in order,
  args passed to the named task, the failing status propagated, and a
  did-you-mean for typos. `clove tasks` lists them; `clove new my-app`
  scaffolds a project (clove.edn, src/, test/, README). `clove.edn`'s
  `:paths` join the load path like `deps.edn`'s.
- **`? question` at the REPL asks Claude.** The question goes out with the
  session transcript (inputs, results, errors, earlier answers; last 32 KB)
  and a system prompt describing clove; the answer streams in. Claude's tools:
  `clove_lookup` (doc/source/dir/apropos/find-doc/var value — read-only, runs
  without asking) and `propose_form`, which writes one line into your prompt
  to edit, run with Enter, or decline with `^C` — Claude never runs code
  itself. The answer is numbered like a result and held in `*1`. Implemented in
  the new `claude.clj` battery (`(claude/ask "...")` works from code too) over
  `curl`; needs `ANTHROPIC_API_KEY`. Uses `claude-opus-5-5` with server-side
  refusal fallbacks. POSIX shells only for now (the key is expanded as `$VAR`).
- **`clove/sh-lines*`** runs a shell command and calls a fn on each output line
  as it arrives (what the streaming above is built on).
- **`make fuzz` / nightly fuzz workflow.** `fuzz/gen_expr.py` generates random
  typed core expressions and `fuzz/fuzz.sh` runs them (plus the string
  corpora) through clove and babashka and diffs the output; a GitHub workflow
  runs it nightly with fresh, printed seeds. Its first runs found the six
  divergences listed under Fixed below.
- `clove -e A -e B` — a repeated `-e` separates expressions (it was read as a
  symbol).
- **`java.math.BigDecimal`**, as a real numeric type: unscaled integer +
  decimal scale, so the arithmetic is exact. `1.5M` literals; `+ - * /`,
  `quot`/`rem`/`mod`, `inc`/`dec`, `compare`, `==` and `=` all accept it, with
  Java's scale rules (`(+ 1.5M 1.5M)` is `3.0M`, `(/ 150M 1E+2M)` is `1.50M`)
  and Java's exact division — `(/ 1M 3M)` throws "Non-terminating decimal
  expansion" instead of guessing. `=` and `hash` go by value, so
  `(= 1.0M 1.00M)` and `#{1.0M}` finds `1.00M`, as in Clojure. Mixed with
  integers or ratios the result stays a BigDecimal; a double makes it a
  double. Prints `1.5M` under `pr`, `1.5` under `str`, with Java's switch to
  `1E+3` notation. `number?`/`decimal?`/`rational?` are true; `bigdec`,
  `double`, `long`, `rationalize` and `format "%.2f"` (exact HALF_UP, so
  `1.005M` → `"1.01"`) all convert it. Interop: `BigDecimal.`,
  `ONE`/`ZERO`/`TEN`, `MathContext.`, `RoundingMode/HALF_UP`, and
  `.add`/`.subtract`/`.multiply`/`.divide`/`.setScale`/`.scaleByPowerOfTen`/
  `.abs`/`.negate`/`.compareTo`/`.precision`/`.scale`/`.unscaledValue`/
  `.toPlainString`/`.doubleValue`. Differentially fuzzed against the JVM:
  2000 random cases x 25 operations, identical.
- **`java.util.Random`**: the JVM's exact 48-bit LCG — `.nextDouble`,
  `.nextLong`, `.nextInt` (both arities), `.nextBoolean`, `.nextFloat`,
  `.nextGaussian` (Marsaglia polar, with Java's cached second deviate),
  `.setSeed`. A seeded stream matches the JVM value-for-value, which is what
  ported numeric code pins its expectations to.
- **Real `double-array`s.** `double-array` was `(def double-array int-array)`:
  callable, but it could not hold a double. Arrays now carry an element-type
  flag and store IEEE bit patterns in the same 8-byte slots — same allocation,
  sweep, and GC treatment, with only the read and write ends aware of which
  kind it is. `float-array` follows; `int-array` is unchanged.
- `Math/signum` (NaN and both zeros pass through, as in Java).

### Fixed
- **`(long ratio)` out of range** throws `IllegalArgumentException` "Value out
  of range for long: <quotient>", as Clojure does, instead of an untyped error
  that any `catch` clause took (found by the nightly fuzzer).
- **`=` between a finite and an infinite seq** returns false instead of
  hanging: `(= [0 1 2] (range))`.
- **Long names, messages and format output are no longer cut off.** Fixed
  C buffers silently truncated: interpreter error messages at 255 chars;
  namespace-qualified names (a `def` in a namespace, `::kw`, `#:ns{}` map
  keys, `(symbol ns name)`, syntax-quote and var lookup) at 255; `gensym`
  names at 63; `format` output with a width (`%5s`) at 511; and a decimal
  literal over 63 chars was a "number too long" error. Each now grows to fit.
  The differential fuzzer generates long names so this stays covered.
- **`%f` of a double formats like Java.** It rounds the double's shortest
  decimal form HALF_UP (`(format "%.1f" 0.25)` is `"0.3"`, `(format "%.20f"
  0.1)` has no `...555` tail), and NaN/infinity print as `NaN`/`Infinity`
  instead of C's `nan`/`inf`. Found by the fuzzer's new long-number cases.
- Found by the new fuzzer: `subvec` now checks its bounds (it clamped
  silently); ratio→double conversion follows Java's `Ratio.doubleValue` (16
  significant digits, then nearest double), so `(/ 1.5 8/2181825)` prints
  `409092.18749999994` as on the JVM; `(str/replace s "" r)` inserts between
  every character instead of erroring; `(long 1e19)` and `long` of an
  out-of-range bigint throw "Value out of range for long" (the cast was
  undefined behavior in C); `(abs Long/MIN_VALUE)` is `Long/MIN_VALUE`.
- **A pending exception survives `finally`.** The exception in flight lived
  only in globals, so a `finally` body that threw and caught an error of its
  own replaced what was rethrown afterwards.
- **Error traces keep the frame of a function that tail-calls a native.**
  `(defn f [x] (+ x 1))` failing inside `+` showed `(+ ...)` directly under
  `f`'s caller: the native's frame replaced `f`'s instead of stacking on it.
- **Keyword lookup missed sorted maps and sets.** `(:k (sorted-map ...))` and
  `(:k #{:k})` returned nil while `(get ...)` found the entry — a silent wrong
  answer. Clojure's `Keyword.invoke` is exactly `RT.get`, so it now delegates
  there and covers every collection `get` covers.
- **Map literals now evaluate in source order.** Entries were evaluated in
  HAMT hash order, so `{:result (f) :values @acc}` read `acc` *before* running
  `f`. Clojure evaluates small map literals left to right (they are array-maps,
  whose seq is insertion order); the reader now keeps its original k/v form
  list — which it already built — and eval walks that.
- **Integral doubles coerce at index positions**, following `RT.intCast`:
  `(nth v 1.9)` is index 1 and `(subs s 1.7)` drops one character, as in
  Clojure. Numeric code that computes an index in floating point relies on it.
  The JIT/FFI bridge stays strict on purpose — a `^long` function handed 2.5
  must not quietly compute on 2 and disagree with its interpreted twin.
- `format` `%g`/`%G` follow Java, which keeps trailing zeros, rather than C,
  which strips them: `(format "%.4g" 2.0)` is now `"2.000"`. `%E` also works.
- `inc`/`dec` no longer fail on bigints and ratios — `(inc 99999999999999999999N)`
  and `(inc 1/2)` now work instead of erroring on the int64 fast path.
- `bigint` is idempotent: `(bigint (bigint x))` no longer round-trips through
  `int` and caps at int64.
- **`aset` on an int array refuses a non-integer.** Since index positions
  started coercing, `(aset (int-array 2) 0 2.5)` stored `2` without a word;
  it now throws "argument type mismatch" like the JVM. `(int-array [1.5])`
  still truncates, as Clojure's does.
- **Map literals reject keys that collide at runtime**: `(let [x 1] {x 1 1 2})`
  throws "Duplicate key: 1" instead of silently keeping one entry.

## v0.5.1 — 2026-09-13

### Added
- **Shebang support for executable scripts.** `#!` now reads as a comment to
  end of line, matching Clojure's reader (which registers `!` in the dispatch
  table as a comment reader), so `#!/usr/bin/env cljc` + `chmod +x` gives a
  directly runnable script. Because it lives in the reader's whitespace skipper
  rather than in the script entry point, it holds on every path — `cljc
  file.clj`, `load-file`, `require`, stdin, `cljc lint`, `cljc watch`, and the
  source embedded by `cljc bundle` — and anywhere in a file, not only line 1.
  The newline is left for the line counter, so error traces still report
  honest line numbers.

## v0.5.0 — 2026-09-07

### Added
- **`peg.clj`** battery: Janet-style Parsing Expression Grammars —
  `(require '[peg :as peg])`, then `(peg/match '(* (<- :w+) "=" (number :d+)) "k=7")`
  ⇒ `["k" 7]`. Patterns are plain data (quote them; vectors work like lists),
  grammars are maps with `:main`, and the whole Janet vocabulary is there:
  `* + ! any some opt between at-least at-most repeat if if-not look to thru
  sub split backmatch`, captures `<- group replace cmt constant argument
  position line column accumulate number drop backref/-> unref nth only-tags
  error` with tags, and `compile`/`match`/`find`/`find-all`/`replace`/
  `replace-all`. Semantics were checked case-for-case against the real Janet
  binary (144-pair corpus, now in `tests.clj`), including the corners the
  Janet docs get wrong (`if`/`look` keep their captures; a no-progress
  iteration ends `any`/`some`; `to` drops, `thru` keeps). One deliberate
  difference: positions and `(<- 1)` are codepoints, not bytes. `look` with a
  negative offset gives lookbehind, which the regex engine lacks.
- `String.startsWith(prefix, toffset)`: `(.startsWith s pre off)` now takes
  the Java offset form, backed by a new `cljc/str-starts-at?` native that
  compares in place (no substring allocation) — the peg literal matcher's
  primitive.

### Changed
- Non-ASCII strings index through a **cursor cache**: `nth`/`subs`/
  `str/index-of` on a string with multibyte chars used to walk from byte 0 on
  every call, so a sequential scan was quadratic (200k-char scan: 29 s). Each
  string now remembers its last (codepoint, byte) position and steps forward or
  backward from it (69 ms). Backward steps are taken only when the string is
  well-formed UTF-8 (a flag computed with the codepoint count); a stray
  continuation byte would desync a reverse walk, so dirty strings restart from
  the front. ASCII strings were and remain O(1).
- Regex: `(?<` — lookbehind or a named group — is now a loud
  `regex: (?<...) is not supported` error. It used to parse as an ordinary
  group with a stray `?`, so `(re-find #"(?<=a)b" "ab")` silently returned
  nil where Java returns `"b"`.

## v0.4.2 — 2026-09-06

### Fixed
- `compare` on strings, keywords and symbols follows Java's `String.compareTo`:
  the difference of the first differing character, or of the **lengths** when
  one is a prefix of the other. It returned `strcmp`'s raw value, and C
  specifies only `strcmp`'s *sign* — aarch64 glibc's word-at-a-time strcmp
  returns 64 for `(compare "b" "a")` where x86 returns 1. The prefix case was
  wrong on every platform, since strcmp compares against the NUL terminator:
  `(compare "a" "aa")` was `0 - \a` = -97 where the JVM gives -1; x86 only
  looked correct because glibc happens to return the byte difference for
  mismatches there. Sign was always correct, so sorting and comparators were
  unaffected. Found on Linux/aarch64 by the ides.club Claude session; the
  conformance corpus gains a prefix pair and a far-apart-chars pair, which
  would have caught the latent half on x86.

## v0.4.1 — 2026-09-05

### Security
- `http/serve` binds **loopback** by default. It hardcoded `"0.0.0.0"` — every
  interface — so anything served with the battery was reachable from the whole
  network, and no argument could ask for anything else; running the test suite
  exposed its own port 8093 for the duration of the run. Both bindings answer
  identically on `127.0.0.1`, which is why it went unnoticed. `tcp/listen`
  already defaults to loopback and `nrepl.clj` passes it explicitly; this was
  the one place overriding that. Pass a host to opt back in:
  `(serve 8080 h "0.0.0.0")`, likewise `run-server`. **Breaking** for anyone
  relying on `http/serve` being reachable from another machine.

## v0.4.0 — 2026-09-05

### Security
- **FFI module cache was a local code-execution vector on multi-user hosts.**
  It lived at `/tmp/cljc-ffi4-<hash>.so`, where the hash covers only
  `(code, libs, cc)` and is therefore identical for every user on the machine;
  cljc tested `-f` and then `dlopen`ed it with no ownership check. Any local
  user could precompute the hash, plant a `.so`, and have it executed inside
  another user's cljc process. The cache now lives in a private per-user
  directory (`$TMPDIR/cljc-<uid>`, created 0700 and refused if it exists and
  is not a directory this user owns), and a module is loaded only when
  `test -O` confirms this user owns it. Setting `TMPDIR` alone was never a
  mitigation, since the paths were hardcoded. Only reachable by code that
  loads the FFI or JIT batteries.
- `libc.clj` wrote its shim to a fixed `/tmp/cljc_libc_shim.h`. Because `/tmp`
  is sticky, every user after the first could not overwrite it, breaking
  `libc.clj` and `fs.clj` for them; it was also a symlink-follow target. It
  moves into the same private per-user directory.
- Added `(cljc/getuid)` — native, used to scope that directory.

### Added
- `(cljc/now-us*)` — wall-clock microseconds since the Unix epoch, as an exact
  int64. Distinct from `now-ms*`, which stays `CLOCK_MONOTONIC` because it
  backs `fiber/timer!`, `csp/timeout` and every deadline: a wall-clock deadline
  stalls when NTP steps the clock. Returned as an integer, not a double — at
  epoch magnitude a double's ulp is ~0.5 µs, so microseconds would not survive.
- `(cljc/getpid)` — native, so no `require` and no `dlopen`, and it works on
  Windows (`GetCurrentProcessId`) where `libc.clj`'s FFI binding self-skips.
- `cljc test` with no file arguments discovers `*_test.clj`/`*_test.cljc` under
  `.`, sorted, skipping `vendor`/`target`/`node_modules`/`out` and dotted dirs,
  and prints what it found. Explicit arguments still win.
- `make test-full` runs the GC-stress pass; `make test` no longer does.
- Raw terminal input natives for TUIs, no FFI needed: `(cljc/raw-mode* bool)`
  enters/leaves raw mode (false when stdin isn't a tty; an `atexit` hook
  restores the terminal on a hard exit), `(cljc/read-key*)` blocks for one
  keypress and returns its bytes — a whole UTF-8 character or a whole escape
  sequence (`"\e[A"` for Up; a lone Esc resolves after 30 ms), nil on EOF —
  and `(cljc/term-size*)` gives `[rows cols]`. Same termios recipe as the
  REPL's line editor. Windows builds get no-ops.
- **Specter runs** (`com.rpl/specter` + `riddley`): see COMPATIBILITY.md for the
  92-case battery. The bring-up fixed six general interpreter issues below.
- Reader conditionals honor the `:bb` feature (priority `:cljc` > `:bb` >
  `:default` > `:clj`): a library's babashka branch is its JVM-class-free
  path, which is what cljc can run.
- Host class names for protocol dispatch: `java.util.List`/`Set`,
  `clojure.lang.PersistentTreeMap`/`PersistentTreeSet`, `IReduce`,
  `ITransientVector`, `Cons`, `IRecord` (every `defrecord` type derives it),
  `Sorted`; sorted maps/sets derive the map/set interfaces.
  `(type (transient []))` is `:transient-vector` (was `:unknown`).

### Changed
- `System/exit` **actually exits**. It was `(defn System/exit [n] nil)`: it
  returned nil *and let the script run on*, so `cljc script.clj || echo failed`
  never fired whatever the script did. At a REPL prompt or inside an nREPL eval
  it unwinds with an error instead of terminating, so a loaded file calling
  `System/exit` cannot kill an editor's connection.
- `System/currentTimeMillis`, `java.time.Instant/now` and `java.util.Date.`
  report **wall-clock time**. They were derived from the monotonic `now-ms*`,
  whose origin is boot, so they returned the machine's uptime — `Instant/now`
  answered `1970-01-16T…`. `System/nanoTime` is unchanged: Java specifies it as
  monotonic, so deriving it from `now-ms*` was already correct.
- `Integer/toString` with a radix is **signed** (`-101`, was `101`) and
  `Integer/toBinaryString` is **unsigned** two's complement over 32 bits
  (`(Integer/toBinaryString -1)` → 32 ones, was `"1"`); new
  `Long/toBinaryString` is the 64-bit form. These are different functions on
  the JVM and one had been defined as the other, which dropped the sign: `+2^54`
  and `-2^54` printed identically.
- `babashka.cli` short flags **take a value**, as real babashka.cli does with or
  without `:alias` — `-n 2` is `{:n 2}`, was `{:n true}`. The value had also
  fallen through to the positional branch, so `-n 2 file` yielded
  `{:args ["2" "file"]}`. **This changes existing parses**: a boolean short flag
  followed by a positional argument now consumes it; declare booleans via
  `:coerce` or use `--flag`.
- `cljc test` with no arguments and no test files found exits **1**, not 0. A
  green result that tested nothing passes CI whether or not the tests exist.

### Fixed
- A top-level `(do ...)` evaluates its subforms one at a time, as JVM Clojure
  does: `(do (defmacro m ..) (m ..))` — e.g. a `#?(:clj (do ...))` body — saw
  the macro use compiled before the `defmacro` ran (the head deopted at
  runtime but its args had been compiled as evaluations: "I don't know what
  `richnav` refers to").
- Macro aliases through a Var work: `(def alias (var m))` +
  `(alter-meta! #'alias merge {:macro true})` (specter's `defmacroalias`) now
  expands as a macro everywhere (tree-walker, VM, `macroexpand-1`, `macro?`);
  it used to be called as a plain fn returning the expansion unevaluated.
- The empty vector is a singleton like `PersistentVector/EMPTY`:
  `(identical? [] (vector))`, `(empty v)`, `(pop [x])`,
  `(persistent! (transient []))` all hold (specter's `terminal*` relies on
  it). Metadata on an empty vector takes a private copy.
- `fn` bodies whose list spine ends in a lazy seq — macro-built with
  `(cons params (drop 2 m))`, `list*`, `concat` — evaluated to **nil**: the
  clause/body walkers stepped raw cons tails and read the lazy tail as an
  empty body. Spines are realized in place (the arity cache survives).
- `reify` with several clauses of the same method name (IReduce's 2- and
  3-arg `reduce`) kept only the last; they now form one multi-arity fn as in
  `deftype`. `reduce`, `into` and `transduce` honor a reify/deftype's own
  `reduce`; the C-side deftype method dispatch (`count`/`nth`/`get`/…) also
  finds methods on reify instances.
- **GC**: the tree-walker's `loop` did not root its `recur` sentinel across
  the rebinding allocations; an optimizing build could keep only the pointer
  into the sentinel's heap-spilled argument array (invisible to the
  conservative scan) and the sweep freed it mid-iteration (ASan `-O1`
  heap-use-after-free; `-O0` hid it). Now `volatile`-kept like `apply`'s.
- Macros expand under the namespace their call site was *written* in. cljc
  expands lazily (first call of the enclosing fn), so a macro that reads
  `*ns*` or `(ns-aliases *ns*)` inside a library fn first called from `user`
  saw `user` — JVM Clojure expands at definition time. `*ns*` is restored
  afterwards, including when the expansion throws.
- Windows CI: three `spit`/`load-file` calls used absolute POSIX paths, which a
  mingw build resolves against the current drive root — the suite aborted there.
  The portable one now uses a relative path (and cleans up after itself); the
  two that need a POSIX shell and the unix binary name are `when-unix` gated.
- The nREPL test's read loop is bounded by a deadline. `csp/recv!` parks on
  POLLIN with no timeout, so a server fiber dying without closing the socket
  wedged the whole suite at 100% CPU — and any `./install.sh` running it.

## v0.3.0 — 2026-08-27

### Added
- **Agents**: `agent`/`send`/`send-off`/`send-via`, `await`/`await-for`,
  `agent-error`/`restart-agent` (`:clear-actions`; a failed agent holds its
  queue), error modes (`:fail` default, `:continue` with `:error-handler`),
  `release-pending-sends`, `shutdown-agents`. Actions run serially per agent
  on a drain fiber (needs coroutines at runtime, like `future`).
- **STM**: real `ref`/`dosync`/`alter`/`ref-set`/`commute`/`ensure` replacing
  the atom-backed shims — MVCC-lite with per-ref versions: commit verifies
  written and `ensure`d refs and retries the body on conflict (possible when
  a transaction yields — sleep/deref/io — while another fiber commits);
  `commute` re-applies on the latest value without conflicting; sends inside
  a transaction are held until commit; `io!` throws inside a transaction;
  nested `dosync` joins the outer transaction. `alter`/`ref-set`/`commute`/
  `ensure` outside `dosync` now throw (they used to silently mutate).
  The current transaction is fiber-local (keyed by the fiber, not a dynamic
  var — a `binding` would leak across yields to other fibers).
- **Watches**: `add-watch`/`remove-watch` are real on agents and refs
  (fired per action / at commit); still no-ops on atoms.
- `cljc bundle` entrypoint parity with `jolt build -m` / `bb -m`: the bundled
  binary now calls the script's `-main` with the command-line args when one
  is defined (top-level forms still run first; scripts without `-main` are
  unchanged), and an integer return from `-main` becomes the exit status.
  New `cljc bundle -m <ns> <out>` bundles a namespace from `*load-path*`
  (plus its transitive requires) and entrypoints its `-main`. `cljc -m` now
  honors the integer-exit rule too.
- **`cljc bundle --library`**: build a script into a shared library
  (`.so`/`.dylib`/`.dll`) with a C ABI instead of an executable —
  `cljc_lib_init()` runs the embedded script, `cljc_lib_eval(src)` returns
  `pr-str` of the last form (`NULL` on error, interpreter survives),
  `cljc_lib_last_error()` reports; a matching `<out>.h` header is generated
  for C/C++/Rust/Zig hosts (see `examples/libhost.c`).

- **`cljc vendor` understands deps.edn**: `cljc vendor` with no argument (or
  a deps.edn path) resolves the `:deps` map transitively without Java or
  `~/.m2` — pinned `:mvn/version` jars from Clojars/Maven Central followed
  through their poms (compile/runtime scope, property-versioned/optional/
  `org.clojure/clojure` skipped, Java-only jars tolerated), `:git/url` +
  `:git/sha`/`:git/tag` clones (URL inferred for `io.github.*`/`com.github.*`
  names, `:deps/root` respected), `:local/root` copies; git/local deps
  recurse into their own deps.edn. First coordinate wins; everything lands in
  `./vendor/` and gets the usual try-each-namespace load report. Maven
  mirrors (incl. `file://`) via the `cljc/vendor-repos*` atom.

- **Conformance corpus**: `fuzz/conformance.txt` — 412 curated clojure.core
  expressions whose printed value must match JVM Clojure exactly, diffed
  against a golden file by `make conformance` (part of `make test`; verified
  live against babashka when `bb` is on PATH). Already caught and fixed five
  divergences: `(partition-all n step coll)`, 3-coll `interleave`,
  `.toUpperCase`/`.toLowerCase`, `rationalize` (was identity; now derives
  exact ratios from the shortest decimal repr), and `range` element types
  (start/step promotion decides element type, a double end only bounds —
  `(range 2.5)` is `(0 1 2)`, `(range 0 2 0.5)` starts with int `0`).

- **JIT type hints + doubles**: the reader now keeps `^Tag` hints as
  `{:tag Tag}` metadata on symbols (read-time, like Clojure; still discarded
  on non-symbol forms), and `jit.clj` honors JVM-style `^long`/`^double`
  hints on params and the fn name — hinted functions compile to unboxed
  `double`/`long long` machine ops, with the return type inferred from the
  body when unhinted. Also new in the JIT subset: `/` (double division),
  `Math/sqrt`, `(double x)`/`(long x)` casts, unary minus.

### Fixed
- `compare` on vectors orders by count first, then element-wise, like Clojure
  (`(compare [2] [1 10])` is -1; `(sort [[2] [1 10] [1 2]])` puts `[2]` first)
- **STM**: `commute` on a ref also `alter`ed/`ref-set` in the same transaction
  was applied twice at commit (`(dosync (alter r inc) (commute r inc))` on
  `(ref 0)` gave 3, not 2, and fired watches twice); commutes now skip refs in
  the write set, as in Clojure.
- `cljc bundle` rejected every flag `bundle.clj` documents (`--static`,
  `--windows`, `--cc=`, `--libs=`, `--cflags=`) — the C dispatcher demanded
  exactly two arguments, so cross-compilation was unreachable from the CLI;
  a bundled script that threw still exited 0 (now 1)
- **Memory safety**: int-array bounds checks truncated 64-bit indices to 32
  bits (`(aget a 4294967296)` and `(get a 4294967296)` read/wrote out of
  bounds — segfault or heap corruption); printing bigints over ~280 digits overflowed a heap buffer
  (`(str (apply *' (range 1 201)))`); a source file ending right after `^`
  crashed the reader (NULL deref)
- `binding` now installs in parallel like Clojure (inits can't see earlier
  new values) and no longer permanently leaks earlier bindings when a later
  init throws or a var fails to resolve
- Deftype method dispatch for `.length`/`.charAt`/`.subSequence`/`.close` was
  dead — a `cljc/dt-method` redefinition had swapped its parameters, so e.g.
  `with-open` never called a deftype's own `close`; unified into one
  definition reading `cljc/deftype-methods`
- `record?` no longer returns true for deftype instances (consults the
  `cljc/record-types` registry the rest of the file already maintains)
- Regex: `(?i)`/`(?m)`/`(?u)` now raise "unsupported flag" instead of being
  silently stripped (wrong matches); a nested regex inside a `str/replace`
  replacement fn no longer clobbers the outer pattern's `(?s)` flag;
  `str/split`/`replace`/`replace-first` only treat a string as a pattern when
  it carries the `:regex` meta tag, not any metadata
- `read-line` no longer silently splits lines longer than 4095 bytes
- Conformance corpus: `(- 1/2 1/2)` was in the golden as `0` while JVM Clojure
  gives `0N` (ratio→integer collapse yields a BigInt; cljc's small-N demotion
  is a documented divergence, so the case is replaced by `(- 3/2 1/4)`); the
  babashka re-verification in `make conformance` now fails on disagreement
  instead of being swallowed
- `(keyword nil "a")` returns `:a` (was an error), matching `symbol`
- `(int-array 4294967296)` errors instead of silently making a 0-length array
- Under-arity native calls (`(atom)`, `(mod 5)`, `(subseq ss)`, …) raise
  "wrong number of args" instead of reading stale stack slots
- `.contains` works on the `HashSet.`/`HashMap.` atom shims again (a later
  string-oriented redefinition had dropped the atom branch)
- `cljc watch`: `$`/`` ` `` in file names no longer shell-expand
- bundle: non-ASCII scripts embed as UTF-8 bytes, not codepoints (bundled
  binaries with unicode literals were corrupted); new `cljc/str-bytes*` /
  `cljc/str-nbytes*` natives expose byte-level string access
- http.clj: `Content-Length` is now a byte count (non-ASCII bodies were
  mis-framed/truncated on both server and client side)
- json.clj: `\uXXXX` escapes decode fully, including surrogate pairs (were
  always replaced with `?`)
- jit.clj: `(- x)` compiled to identity (dropped the negation); chained
  comparisons `(< a b c)` compiled to C's `t1 < t2 < t3` instead of
  pairwise-AND
- `csp/merge` with no input channels closes its output immediately
- Removed nine dead duplicate prelude definitions (later defn silently won);
  fixed `process/sh`, `process/shell`, `fs/list-dir`, `http/serve`,
  `http/run-server`, `json/parse` docstrings that described behavior the
  code doesn't have

## v0.2.0 — 2026-07-07

First tagged release. Everything below shipped since the version string was
minted; highlights only — `PLAN.md` carries the engineering log.

### Language & runtime
- Bytecode VM for hot functions and top-level forms, proper tail calls,
  primitive int64 arrays (`int-array`/`aget`/`aset`), chunked lazy
  `range`/`repeat`/`repeatedly`, real `clojure.data.priority-map`
- **Codepoint-indexed strings** (UTF-8 storage, O(1) ASCII fast path) and a
  **codepoint-aware regex engine** — unicode class ranges, astral chars are
  one char (documented divergence from JVM UTF-16). Differentially fuzzed
  against babashka: 1,200 string/regex expressions, zero divergences
- Fiber-backed concurrency: `future`/`promise`/`Thread/sleep` +
  `clojure.core.async` (`go`/`<!`/`>!`/`alts!`) on one cooperative scheduler
- JVM interop shims: epoch-backed `java.time.Instant` with real ISO-8601,
  `DateTimeFormatter/ISO_INSTANT`, v4 `random-uuid`, boxed-number methods,
  class keys for `(extend Cls …)` — vendored `clojure.data.json` runs
  unmodified

### Errors & debugging
- Uncaught errors show the source line with a caret, a `file:line` trace that
  reaches **inside** compiled fn bodies, typo suggestions, and — when an
  unresolved symbol's namespace exists on the load path — the actual fix:
  `try (require 'clojure.set) first`
- Type errors name the value (`expected a number, got a keyword: :kw`);
  stack overflows suggest `loop/recur`
- `(dbg expr)` / `#p expr` print `#dbg file.clj:12 (* x x) => 49` and pass the
  value through; `trace-vars`/`untrace-vars` give depth-indented call traces;
  `*e` holds the last REPL error and `(pst)` reprints it

### REPL
- `(doc x)` with arglists (natives and special forms included), `(source x)`,
  `(dir ns)`, `apropos`, tab completion, Ctrl-R history search, paren-depth
  continuation indent, pretty-printed wide results, numbered results
  (`*1 *2 *3`, `(*results* n)`)
- **Ctrl-C interrupts evaluation** instead of killing the session; printing an
  infinite seq shows `(0 1 … 99 ...)` instead of hanging
- Windows gets the full line editor (Windows 10+ VT console; plain-line
  fallback)

### Tooling
- `cljc vendor <coord>` / `(vendor! "user/repo")` — fetch a pure-Clojure
  library from Clojars (release jar) or GitHub into `./vendor/` and report
  which namespaces load
- `cljc watch file.clj` (rerun on save), `cljc doctor` (load-path triage),
  `cljc nrepl` with nREPL-0.8 `completions`/`lookup` (+ CIDER aliases),
  notebook mode, `cljc bundle` (script + runtime → one native binary)

### Platforms
- CI-tested on Linux (+ ASan/UBSan), macOS arm64 + x86_64 (Rosetta), and
  Windows (mingw-w64); the suite runs twice everywhere (normal + GC stress)
- Tagged releases ship portable archives per platform: unpack anywhere and
  run — batteries and vendored libraries resolve relative to the binary
