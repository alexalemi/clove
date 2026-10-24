# clove

A small Clojure in a single C file. Inspired by [Janet](https://janet-lang.org/)'s
embeddability, aimed at [babashka](https://babashka.org/)-flavored scripting and
beyond: persistent collections, macros, destructuring, protocols, ratios,
namespaces, coroutines, regex — in one ~18,000-line C99 file with no dependencies.

```clojure
(defn top-words [text n]
  (->> (re-seq #"\w+" (str/lower-case text))
       frequencies
       (sort-by (fn [[_ c]] (- c)))
       (take n)))

(doseq [[word count] (top-words (slurp "moby-dick.txt") 5)]
  (println (format "%-12s %d" word count)))
```

It's grown well past a scripting toy: real libraries run on it unmodified —
[Emmy](https://github.com/mentat-collective/emmy) (a computer-algebra system),
[DataScript](https://github.com/tonsky/datascript) (Datalog),
[core.logic](https://github.com/clojure/core.logic) (miniKanren),
[core.async](https://github.com/clojure/core.async),
[hiccup](https://github.com/weavejester/hiccup),
[instaparse](https://github.com/Engelberg/instaparse), and ~30 of babashka's
built-in namespaces. See [Running real libraries](#running-real-libraries).

## Quick start

```sh
make            # builds ./clove (cc, -lm -ldl, nothing else)
./clove          # REPL: editing, history, tab-completion, highlighting, *1 *2 *3
./clove file.clj # run a script
make test       # 2000+ assertions + conformance corpus (~20s)
make test-full  # adds the CLOVE_GC_STRESS=1 pass (~8x slower; CI/pre-release)

./install.sh                      # install to ~/.local (no sudo)
PREFIX=/usr/local ./install.sh    # system-wide
```

Installed, `clove` works from anywhere: batteries (`json.clj`, `fs.clj`,
`process.clj`, `peg.clj`, …) and vendored libraries (`clojure.set`, `clojure.tools.logging`,
`nextjournal.markdown`, medley, …) resolve through `$PREFIX/share/clove`, with
`CLOVE_PATH` (colon-separated) for extra roots. An uninstalled `./clove` also
works from any directory (its own directory joins the load path). When requires
misbehave, `clove doctor` prints the resolved load path and where the batteries
were found.

## Day-to-day niceties

**Discovery, in the REPL.** `(doc map)` — docstring *and* arglists, including
special forms (`(doc if)`) and natives. `(source take-while)` reconstructs a
fn's stored definition. `(dir clojure.string)` lists a namespace's publics
(auto-requiring it). `(apropos "index")` and `(find-doc "lazy")` search.
Tab completes, `Ctrl-R` reverse-searches history, `↑↓` walk it, results are
numbered (`*1 *2 *3`, `(*results* n)`), and wide results pretty-print to your
terminal width. `(inspect x)` browses a value full-screen — drill into maps,
vectors, atoms and infinite seqs, `q` hands back what you were looking at.

**Editing.** The prompt is a small multi-line editor: Enter runs a complete
form and otherwise opens an indented new line (Alt-Enter always does), ↑ brings
back whole multi-line forms, brackets and quotes auto-pair, pastes land
verbatim, and the arglists of the call you're typing show dimly after the
cursor. vi users get vi mode — insert/normal, motions, `d`/`c`/`y` with counts
and text objects (`ciw`, `di(`, `ya"`), `u`, `.` — when `~/.inputrc` says
`set editing-mode vi`, `CLOVE_EDIT_MODE=vi` is set, or after
`(clove/edit-mode :vi)`.

**Debugging.** Wrap any subexpression in `(dbg expr)` — or use the reader
shorthand `#p expr` — and it prints `#dbg file.clj:12 (* x x) => 49` while
returning the value, so code keeps working while you watch it. After an error,
`*e` holds the exception and `(pst)` reprints its message and trace. `(pprint x)`
is a real pretty-printer (fill layout, aligned map values).

**Ask Claude, in the REPL.** A line starting with `? ` goes to Claude instead
of the evaluator: `? why did [3] throw` sends the question with this session's
transcript (inputs, results, errors, earlier answers) and a standing prompt
about clove, and the answer streams in as it's written. Claude can look things
up on its own — `doc`, `source`, `dir`, `apropos`, `find-doc`, a var's value —
but never runs code itself: when running something would help, it writes the
line into your prompt (`claude⟩ (reduce + (filter odd? xs))`), and you edit
it, press Enter to run it, or `^C` to decline. It sees what you ran and the
output. The answer lands in `*1`. Needs `curl` and `ANTHROPIC_API_KEY`; model
and effort live in `claude/config` (see `claude.clj`). `?x` alone is still a
symbol, so logic variables are unaffected. A bare `?` right after an error
asks Claude to explain it.

**Errors that point somewhere.** Uncaught errors show the offending source line
with a caret, a trace with exact lines that reaches *inside* function bodies
(`at (nth ...) other_file.clj:2`), typo suggestions (`Did you mean `println`?`),
and — when an unresolved symbol's namespace exists on the load path —
the actual fix: `try (require 'clojure.set) first`. Stack overflows say
"deep non-tail recursion? use loop/recur". Reader errors carry `file:line`.

**Workflow subcommands.**

```sh
clove new my-app          # scaffold: clove.edn, src/, test/
clove tasks               # list clove.edn's :tasks
clove run test            # run one (deps first; args go to the task)
clove watch script.clj    # rerun on every save (fresh process each run)
clove vendor weavejester/medley                  # or a full git URL:
clove vendor https://github.com/clojure/data.json
clove vendor              # resolve ./deps.edn's :deps, transitively
clove doctor              # why isn't my require finding anything?
```

`clove vendor` resolves `group/artifact` against Clojars first (latest release
jar, extracted with `unzip`/`bsdtar`), falling back to a GitHub shallow clone;
sources land in `./vendor/` (already on the load path), then it *tries each
namespace* and reports which load cleanly. With no argument (or a deps.edn
path) it reads `deps.edn` instead and resolves the whole `:deps` map,
transitively: `:mvn/version` is fetched pinned from Clojars/Maven Central and
followed through the jars' poms (compile/runtime scopes; `org.clojure/clojure`
and Java-only jars are skipped gracefully), `:git/url` + `:git/sha`/`:git/tag`
clones and checks out (`io.github.*`/`com.github.*` names infer the URL,
`:deps/root` respected), `:local/root` copies — and git/local deps recurse
into their own `deps.edn`. No Java, no `~/.m2`, first coordinate wins. Common JVM interop is shimmed —
`java.time.Instant`/`DateTimeFormatter` (epoch-backed, real ISO-8601),
`java.util.UUID` (real v4 `random-uuid`), boxed-number methods, class keys for
`(extend Cls …)` — enough that e.g. `clojure.data.json` vendors and round-trips
unmodified, including non-ASCII text (astral chars in its `\uXXXX` escaping
assume UTF-16 surrogates — pass `:escape-unicode false` for those, or use the
UTF-8-native `json.clj` battery). Heavier interop may still need porting.

**Executable scripts.** `#!` is a reader comment (as in Clojure's reader), so a
script can name its own interpreter and be run directly:

```clojure
#!/usr/bin/env clove
(println "hello," (or (first *args*) "world"))
```

```sh
chmod +x hello && ./hello there      #=> hello, there
```

`*args*` holds the arguments after the script path, and `clove bundle hello out`
compiles the same file — shebang and all — into a standalone binary. Since `#!`
comments run to end of line *anywhere*, not just on line 1, a shebang survives
`load-file`, `require`, `clove lint`, and piping through stdin.

`/usr/bin/env` accepts no interpreter arguments on Linux. When the script needs
to choose its own interpreter — a repo-local `./clove`, a wrapper, an env var —
use the `sh` trampoline: `sh` sees `#_(` as a comment and reaches the `exec`;
the reader sees a discarded form and reaches the code.

```clojure
#!/bin/sh
#_(
   exec "${CLOVE:-clove}" "$0" "$@"
   )
(println "args:" (vec *args*))
```


## Platforms

CI builds and runs the full suite (normal + GC-stress) on every push:

| Platform | Status |
|---|---|
| Linux | full — the reference platform (plus an ASan/UBSan job) |
| macOS arm64 + x86_64 | full, including coroutines/csp (ucontext) |
| Windows (mingw-w64) | core language, collections, lazy seqs, regex, sockets, full line-editing REPL (Windows 10+ VT console; plain-line fallback); **no** coroutines/`csp`/`core.async`/nREPL |

The test suite probes capabilities at startup (`clove-test-unix?`,
`clove-test-coro?` in `tests.clj`) and self-skips what a platform lacks, so the
same `tests.clj` runs everywhere.

## What's inside

| Area | Coverage |
|---|---|
| **Numbers** | int64 with **checked overflow** (`+ - * inc dec` throw "long overflow" like Clojure; `unchecked-*` wrap), **BigInts** with Clojure's contagion (`1N` literals, `(+ 1N 1)` ⇒ `2N`, the auto-promoting `+'`/`-'`/`*'`), doubles, **ratios** (`3/4`, `(/ 22 7)` ⇒ `22/7`, `numerator`/`denominator`) |
| **Lazy seqs** | `lazy-seq`, infinite `range`/`iterate`/`repeat`/`cycle`/`repeatedly`, lazy `map`/`filter`/`take`/`concat`, true call-by-need, 32-element chunking |
| **Data** | nil, bools, strings (escapes), symbols, keywords, lists, **persistent vectors** (32-way tries + tail, transients `transient`/`conj!`/`assoc!`/`persistent!`), **persistent maps** (HAMT), **persistent sets**, **sorted maps/sets** (weight-balanced trees: `subseq`/`rsubseq`/`sorted-set-by`), atoms |
| **Special forms** | `quote if do def defn defmacro let fn loop recur and or when cond try/catch/finally quasiquote var` |
| **Macros** | `defmacro` + quasiquote (`` ` `` `~` `~@`, splices into lists/vectors/maps/sets), **auto-gensym `x#`**; `->` `->>` `some->` `cond->` `as->` `case` `condp` `for` `doseq` `dotimes` `doto` `letfn` `with-out-str` `with-redefs` … most written in clove itself (the prelude) |
| **Functions** | multi-arity `(fn ([x] …) ([x y] …))`, variadic `& rest`, full destructuring (`[a b & r :as v]`, `{:keys [x] :or {…} :as m}`), `:pre`/`:post` conditions |
| **Namespaces & vars** | per-namespace `:as`/`:refer` aliases, first-class **vars** (`#'x`, `var?`, `resolve`, `alter-var-root`, `with-redefs`), a real `user` namespace at the top level, `*ns*`/`in-ns`/`find-ns`/`all-ns`/`ns-publics`/`ns-interns`/`ns-aliases`/`ns-resolve`, **`^:private` enforced** across namespaces (`#'a/x` var access stays open, like Clojure) |
| **Polymorphism** | `defmulti`/`defmethod`, `defprotocol`/`extend-type`/`extend-protocol`/`satisfies?`, **`deftype`/`defrecord`** with real method dispatch (this is what lets Emmy/DataScript/instaparse run), `binding` |
| **Errors** | `throw` any value, `try`/`catch`/`finally` with **typed `catch`** (first clause the exception is-a; `class`/`instance?` agree), `ex-info`/`ex-message`/`ex-data`; interpreter errors are catchable. Friendly messages: arity errors name the fn + accepted arities, type errors name the offending value's type, unresolved symbols get typo/require suggestions; uncaught errors render the source line + caret and a `file:line` trace that reaches inside compiled fn bodies (`*e`/`(pst)` for the post-mortem) |
| **I/O streams** | `print`/`println`/`pr`/`prn` route through the **`*out*`** var; bind it to capture (`with-out-str`) or to `*err*` for stderr; `slurp`/`spit`, string readers/writers |
| **Regex** | self-contained backtracking engine, **codepoint-aware** (`.`/classes/quantifiers match whole UTF-8 chars; `[α-ω]` unicode ranges work; `\w`/`\d`/`\s` stay ASCII like Java's default): classes, groups, `(?:…)`, lookahead `(?=)`, alternation, `{n,m}` quantifiers, lazy quantifiers, **backreferences `\1`…`\9`**; `#"…"` literals; `re-find` `re-matches` `re-seq` `re-replace` (`$1` refs) `re-split`; guarded against catastrophic backtracking |
| **PEGs** | `peg.clj` battery: [Janet's](https://janet-lang.org/docs/peg.html) parsing-expression grammars as plain data — `(peg/match '(* (<- :w+) "=" (number :d+)) "k=7")` ⇒ `["k" 7]`; grammars as maps with `:main`, ordered choice, `any`/`some`/`between`, `if`/`if-not`/`look` (arbitrary lookahead *and* lookbehind), `to`/`thru`, `sub`/`split`, captures with tags (`<-` `group` `replace` `cmt` `accumulate` `number` `backmatch` `->` `unref` …), `find`/`find-all`/`replace`/`replace-all`; codepoint-indexed; verified case-for-case against the Janet binary |
| **Transducers** | `map`/`filter`/`take`/`mapcat`/… as transducers, `transduce`, `eduction`, `into` with an xform — compose over infinite seqs with `reduced` early-exit |
| **Concurrency** | stackful **coroutines** (`coro/new`/`resume`/`yield`) under a shared cooperative **fiber scheduler** (Janet `ev/`-style: ready queue, timer wheel, `poll(2)` fd parking). Real **`future`/`promise`** with blocking `deref` (parks in a fiber, drives the loop on main, `(deref x ms tval)` timeouts, failed futures rethrow at deref), fiber-aware `Thread/sleep`, a genuine `clojure.core.async` (`go`/`<!`/`>!`/`alts!`, channels, timeouts), **agents** (`send`/`await`/error modes/`restart-agent`, serial actions on a drain fiber), and **STM refs** (`dosync`/`alter`/`commute`/`ensure` with real conflict-detect-and-retry across fiber yields; in-tx `send`s held until commit; watches on agents/refs) — all on one thread, one scheduler |
| **Library** | hundreds of core fns: seq ops (`map filter reduce group-by frequencies partition …`), string ops, `format`, `sort`/`compare`, `read-string`/`eval`, `Math/*` |
| **Memory** | mark-and-sweep GC over pooled cells, conservative C-stack scanning (interpreter C code needs no root registration), structural sharing throughout, adaptive collection floor for high-churn workloads |
| **Execution** | tree-walking evaluator with a **bytecode VM** for hot functions, plus proper tail calls (general TCO, beyond Clojure) and `trampoline` |
| **Modes** | REPL, script file, piped stdin, embedded C library, **nREPL server** |

## Editor integration (nREPL)

```sh
./clove --nrepl        # serves nREPL on 127.0.0.1:7888, writes .nrepl-port
./clove --nrepl 7999   # custom port
```

Speaks bencode nREPL (`clone` `describe` `eval` `load-file` `close`
`interrupt` `ls-sessions`), so Conjure, CIDER, and Calva can connect:
evaluation results come back as `value`, `println` output as `out`
messages, errors as `err`. Definitions persist across evals. One client
at a time, loopback only.

Connect in 30 seconds (the server writes `.nrepl-port`, which most clients
auto-detect):

- **VS Code / Calva** — "Calva: Connect to a Running REPL Server", choose
  "Generic", accept the detected port.
- **Emacs / CIDER** — `M-x cider-connect-clj`, host `localhost`, the port
  from `.nrepl-port`.
- **Neovim / Conjure** — open a `.clj` file; it picks up `.nrepl-port`
  automatically.
- **vim-iced** — `iced connect <port>`.

Expect eval/load-file to work everywhere; completion/introspection middleware
varies by client (clove answers the core ops, not the full CIDER middleware
set).

## JIT: compile hot functions to native C

```clojure
(load-file "jit.clj")
(jit/defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(jit/compile! 'fib)    ; generate C → cc → dlopen → swap the binding, live
(fib 32)               ; 1.24 s interpreted → 6 ms native (faster than bb/JVM)
```

`jit.clj` (~250 lines of clove) compiles a numeric subset — `if`, `let`,
`loop`/`recur`, self-recursion, arithmetic/comparisons, `Math/sqrt`, casts —
to unboxed C through the same generate→`cc`→`dlopen`→rebind pipeline as the
FFI. Types default to `long long`; JVM-style `^long`/`^double` hints on
params (and optionally the fn name, for the return type — otherwise it's
inferred from the body) pick unboxed `double`, and types flow through
arithmetic like C promotion:

```clojure
(jit/defn ^double dist [^double x ^double y]
  (Math/sqrt (+ (* x x) (* y y))))
(jit/compile! 'dist)   ; unboxed double machine ops throughout
```

Compiled modules are content-cached, so warm compiles are a `dlopen`.
Outside the subset, `jit/compile!` errors cleanly and the interpreted
version stays. fib(32): **6 ms** vs babashka's 540 ms and JVM Clojure's
630 ms — it's real machine code.

## FFI: call C from Clojure

```clojure
(ffi/define [[:double cos [:double]]] {:headers ["math.h"] :libs "-lm"})
(cos 0.0)   ;=> 1.0
```

s7/cload-style: declare C signatures as data, clove generates glue, compiles a
`.so` at runtime, `dlopen`s it, and the C function becomes a clove fn. Modules
cache by content hash; `ffi/defstruct` generates struct accessors. The battery
shelf builds on it: `libc.clj` (the libc surface — `getpid`, `cwd`, `env`…),
`fs.clj` (babashka.fs-flavored, `list-dir` via real `readdir`), `process.clj`
(`sh`/`shell`/`out`), `json.clj`, `http.clj`, `peg.clj` (Janet-style PEGs), plus
`(sh "cmd")` ⇒ `{:exit :out}`.

## Standalone binaries

```sh
./clove bundle.clj myscript.clj mybinary       # one ~300 KB executable, no deps
./clove bundle.clj --static myscript.clj out   # static: no glibc dep, best to share
./clove bundle.clj --windows myscript.clj out.exe   # cross-compile a Windows .exe
```

`bundle.clj` embeds your script — plus every `.clj` it transitively `require`s
or `load-file`s (batteries, vendored libs) — next to the runtime and compiles
the result: a genuinely self-contained binary that runs anywhere, no `vendor/`
or share dir needed. Janet-style deployment. Script arguments arrive as
`*args*`. This is bundling, not compilation: the script still runs on the
interpreter inside.

A bundle is native code for the platform that built it, so match your friend's
machine. Flags steer the compiler:

| target | command | needs |
|---|---|---|
| same Linux/arch | `bundle script out` | nothing extra |
| portable Linux | `bundle --static script out` | removes the `GLIBC_2.xx` failure mode |
| Linux ARM64 | `bundle --cc=aarch64-linux-gnu-gcc --static script out` | `gcc-aarch64-linux-gnu` |
| Windows | `bundle --windows script out.exe` | `mingw-w64` (`x86_64-w64-mingw32-gcc`) |

`--cc=`, `--libs=`, and `--cflags=` (plus the `CLOVE_CC` env var) expose the
compiler directly for any other cross toolchain, e.g. `zig cc`. On Windows the
interactive REPL drops to plain line input and the nREPL server is unavailable —
script execution and bundles are unaffected.

**Shared libraries.** `--library` builds the inverse embedding direction: your
script as a `.so`/`.dylib`/`.dll` with a C ABI, callable from C, C++, Rust,
Zig — anything that can link C:

```sh
clove bundle --library greet.clj libgreet.so   # + generated libgreet.so.h
cc -O2 examples/libhost.c ./libgreet.so -o libhost
```

The library exports `clove_lib_init()` (runs the embedded script, idempotent),
`clove_lib_eval(src)` (returns `pr-str` of the last form, or `NULL` on error —
the interpreter survives and keeps state), and `clove_lib_last_error()`.
Returned strings stay valid until the next call; the usual embedding rules
apply (one interpreter per process, single-threaded). See
`examples/libhost.c`.

## Running real libraries

`require` resolves namespaces against `*load-path*` (`.`, `vendor/`, the share
dir) and `CLOVE_PATH`, loads `.clj`/`.cljc` files once, and registers per-namespace
`:as`/`:refer` aliases. Each namespace's defs resolve own-namespace-first, so
multi-file libraries with internal cross-references load in isolation — `deftype`,
`defrecord`, and protocols are real, which is what unlocks the heavy hitters.

**Bundled** — `require` works out of the box (vendored under the share dir):

| | |
|---|---|
| `clojure.set` `clojure.string` `clojure.walk` `clojure.zip` `clojure.edn` | core utilities, complete |
| `clojure.data` `clojure.datafy` `clojure.math` `clojure.pprint` `clojure.stacktrace` | |
| `clojure.test` | `deftest`/`is`/`testing`/`are`/`run-tests` |
| `clojure.core.async` | `go`/`<!`/`>!`/`alts!`, channels, timeouts (coroutine-backed) |
| `clojure.core.match` | pattern matching |
| `clojure.data.json` / `cheshire.core` | JSON read/write |
| `clojure.tools.logging` / `taoensso.timbre` | console loggers (to `*err*`) |
| `nextjournal.markdown` | markdown → hiccup (pure-Clojure CommonMark subset) |
| `clj-yaml.core` | YAML ↔ Clojure data (pure-Clojure subset; block/flow, `\|`/`>` scalars) |
| `babashka.fs` `babashka.process` `babashka.cli` `bencode.core` | scripting batteries |
| medley, camel-snake-kebab, edamame | |

**Runs with the library's own source on `CLOVE_PATH`** (clone it, point clove at
`src`) — these exercise deftype, multimethods, multi-file graphs, the lot:

| library | what runs |
|---|---|
| **Emmy** | symbolic + numerical calculus, `simplify`, `D` (autodiff), Lagrangian/Hamiltonian mechanics |
| **DataScript** | `q`/`pull`/`transact!` — Datalog over an in-memory db |
| **core.logic** | `run*`/`conde`/`membero`/`appendo` — relational/logic programming |
| **hiccup** / **hiccup2** | HTML generation, CSS sugar, escaping |
| **instaparse** | context-free grammars → parse trees |
| **SCI** | a Clojure interpreter, running *inside* clove |
| **clojure.data.csv** / **clojure.tools.cli** | CSV, command-line parsing |
| **test.check** | generative property testing |
| **minijax** | JAX-style composable function transforms — `grad`/`jvp`/`jet`/`vmap`, symbolic + interval + arbitrary-precision tracing (74/74 tests) |

**Not yet** — these need a JVM library reimplemented from scratch (a real XML
parser, a host serializer): `clojure.data.xml` (StAX), `cognitect.transit`,
`core.rrb-vector`. The line: pure-Clojure libraries — even
big, multi-file, deftype-heavy ones — are reachable; libraries that lean on a
specific Java library for their core work are not, until that library is ported.

**Advent of Code as a stress test**: clove runs a complete set of Clojure
solutions for **every Advent of Code day, 2015–2025 (262 puzzles)** on real
inputs, each producing answers verified against an independent reference
(Python/Janet/JVM Clojure). The campaign doubled as a bug hunt — it surfaced
(and fixed) eight interpreter bugs the test suite and fuzzers had missed,
from a sort re-entrancy segfault to `load-file` scope leakage. (The solutions
reference private puzzle inputs, so they live outside this repo.)

## Tooling

- **Linting**: ships a `.clj-kondo/config.edn` tuned for the dialect.
  `clj-kondo --lint your.clj` works in this repo; name files `.cljc` if they use
  `#?(:clove …)` reader conditionals. For runtime-defined vars (`ffi/define`), add
  a `(declare …)` like `libc.clj` does.
- **Tests**: `clojure.test` is bundled — `deftest`, `is` (with `thrown?`),
  `testing`, `run-tests`. A failed `(is (= …))` prints a path diff of where the
  values differ.
- **Tasks**: `clove.edn` holds `:paths` and `:tasks`, as in `bb.edn`:
  `{:tasks {test "clove test", ci {:depends [lint test] :task (println "ok")}}}`.
- **Dependencies**: `clove vendor <git-url|github-user/repo>` copies a
  pure-Clojure library's sources into `./vendor/` and reports which of its
  namespaces load.
- **Editor/LSP**: `./clove --nrepl` for eval (Conjure/CIDER/Calva); clojure-lsp's
  static features work on clove files via the same clj-kondo analysis.

## Embedding

The model is stb-style: define `CLOVE_NO_MAIN` and `#include "clove.c"` into one
translation unit. Your native functions then have the interpreter's value
constructors in scope, which is what you need to exchange data:

```c
#define CLOVE_NO_MAIN
#include "clove.c"

static Clove *native_add(CloveEnv *env, Clove *args) {
    int64_t a = as_int(args->as.cons.head, "host-add");
    int64_t b = as_int(args->as.cons.tail->as.cons.head, "host-add");
    return mk_int(a + b);
}

int main(int argc, char **argv) {
    clove_set_stack_base(&argc);          /* GC scans the C stack for roots */
    CloveEnv *env = clove_new_env();
    clove_define_native(env, "host-add", native_add);
    clove_eval_string(env, "(println (host-add 2 40))");
}
```

See `examples/host.c` for a fuller demo (C-side state mutated from scripts,
maps built in C, results consumed back). Build it with `make example`.

Embedding rules:
- Call `clove_set_stack_base` with an address near the top of the thread's
  stack **before** evaluating anything (the conservative GC scans from there).
- Don't hold `Clove *` across `clove_eval_string` calls unless the value is
  reachable from the root env (e.g. `def`'d) — the GC doesn't know about
  C globals.
- One interpreter per process (global state); single-threaded.

## Performance

Benchmarks in `benchmarks/` run unmodified on clove, [babashka](https://babashka.org/),
and JVM Clojure, producing identical output (mean wall time, hyperfine, one machine —
treat as orders of magnitude):

| benchmark | clove | babashka 1.12 | Clojure / JDK 21 |
|---|---|---|---|
| startup (hello world) | **2 ms** | 10 ms | 440 ms |
| fib(27), interpreted recursion | 88 ms | **56 ms** | 563 ms |
| 5M-iteration loop/recur | 439 ms | **239 ms** | 563 ms |
| seq pipeline (1M: filter→map→reduce) | 332 ms | **58 ms** | 488 ms |
| build+read 100k-entry map | 231 ms | **107 ms** | 699 ms |
| build 1M-element vector | 223 ms | **107 ms** | 542 ms |
| sort 200k | **126 ms** | 185 ms | 641 ms |
| regex word-frequency | **23 ms** | 23 ms | 632 ms |
| fib(32), ~1 s of compute | 1.00 s | **0.54 s** | 0.63 s |

Honest reading: babashka wins most compute because its `clojure.core` is
AOT-compiled native code — SCI only interprets your glue, while clove interprets
*everything*. That clove stays within ~1.5–4× of bb (and beats it on sort and
startup) is the trade we wanted. JVM Clojure pays ~440 ms of startup, which
dominates at script scale; for long-running compute the JIT inverts everything.
Use clove where its 2 ms startup, ~325 KB binary, zero dependencies, and
embeddability matter; use bb/JVM where throughput does.

## Deliberate divergences from Clojure

- **Strings index by codepoint, not UTF-16 code unit.** `count`/`nth`/`subs`/
  `seq`/`str/index-of` agree with what a human sees: `(count "héllo")` is 5 and
  `(count "𝄞")` is 1 (Clojure says 2 — a surrogate pair). Storage is UTF-8;
  pure-ASCII strings keep O(1) indexing. A slurped binary file still indexes
  byte-per-byte (invalid sequences fall back to one byte = one char)
- `catch` is typed, but coarsely. An exception's class is recorded where it
  is known — `ex-info`, the `(IllegalStateException. …)`-style constructors,
  `assert`, and the interpreter errors with an obvious JVM class (arithmetic,
  index out of bounds, number parsing, nil or a non-number where a number
  goes, stack overflow) — and the first `catch` clause it *is-a* handles it;
  no match rethrows. The remaining interpreter errors are *untyped* and take
  any clause except `ExceptionInfo`, `AssertionError` and `Error`, so
  `(catch SomeSpecificException e …)` around one still works
- There is no separate `java.math.BigInteger`: `biginteger` returns a BigInt
  (same arithmetic as the JVM's, but it prints `3N` where Java prints `3`),
  and `numerator`/`denominator` of a ratio return longs when they fit
- Float division by zero is `##Inf`/`##NaN` whatever the operand types, as in
  compiled Clojure with primitive doubles; boxed JVM math (and babashka) throws
- Hash maps/sets iterate in hash order, not insertion order (use sorted
  collections for ordered traversal)
- Quasiquote auto-gensym (`x#`) works; nested quasiquote levels do not
- `()` is the empty list (not `nil`), but seq fns treat an exhausted seq as
  `nil`, as in Clojure
- Namespaces are a flat global with per-namespace aliases (not separate var
  interning per ns), fronted by a namespace-object layer: a namespace IS its
  symbol (`*ns*`, `find-ns`, `all-ns` all deal in symbols — there's no
  Namespace type to print as `#object[...]`); top-level `user` defs stay
  bare, so they're visible from other namespaces (real Clojure isolates
  them); `Math/sqrt` resolves as a host-method symbol
- Metadata works (`with-meta`/`meta`/`^{}`) and propagates through collection
  ops (`conj`/`assoc`/`into`, incl. transient roundtrips)
- `#?(:clove …)` reader conditionals; `#(…)`, `\a` char literals, `^meta` supported

The live plan (open threads, GC invariants) is in [PLAN.md](PLAN.md); the full
milestone log is in [HISTORY.md](HISTORY.md).

## Testing

```sh
make test                      # normal suite + conformance
make test-full                 # adds CLOVE_GC_STRESS=1 (slow; shakes out GC roots)
make fuzz                      # differential fuzz vs babashka (needs bb + python3)
# sanitizers (flags required by conservative stack scanning, as with Boehm GC):
# -O1 on purpose: at -O0 stale stack slots root values and hide missing GC roots
# built AS ./clove: the suite's subprocess tests re-invoke "<exe-dir>/clove"
cc -g -O1 -fsanitize=address,undefined -o clove clove.c -lm -ldl
ASAN_OPTIONS=detect_leaks=0:detect_stack_use_after_return=0 ./clove tests.clj
make -B clove                   # back to the normal build
```

Every bug fix lands with a regression assertion in `tests.clj`.

## License

[Eclipse Public License 1.0](LICENSE) — the same license as Clojure itself.
You can embed clove in any program, including closed-source ones; only
modifications to clove's own code must be shared under the EPL if distributed.
Vendored libraries under `vendor/` retain their upstream licenses (EPL-1.0,
notices preserved in each file).
