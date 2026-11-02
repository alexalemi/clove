# clove examples

A suite of small, self-contained programs that each exercise a different
corner of the language. Run any of them from the repo root:

```sh
clove examples/mandelbrot.clj
```

For a browsable gallery (every example run, output captured, source
syntax-highlighted) served over HTTP:

```sh
clove examples/serve.clj        # → http://127.0.0.1:8088
clove examples/serve.clj 9000   # custom port
```

The gallery server is itself a clove program — it uses the built-in `tcp/*`
primitives and shells out to `clove` to run each example.

## The programs

| File | What it is | Features it shows off |
|---|---|---|
| `mandelbrot.clj` | ASCII Mandelbrot set | double math, `loop`/`recur` hot loops |
| `life.clj` | Conway's Game of Life | persistent sets as state, `frequencies` |
| `primes.clj` | Infinite primes, Collatz, Fibonacci | `lazy-seq`, infinite `iterate`/`lazy-cat`, `take-while` |
| `nqueens.clj` | N-Queens solver | recursive backtracking, `mapcat` search |
| `sudoku.clj` | Sudoku solver | backtracking + the most-constrained heuristic, `clojure.set` |
| `calc.clj` | Arithmetic expression evaluator | recursive-descent parsing, regex tokenizing |
| `brainfuck.clj` | A complete Brainfuck interpreter | mutable arrays (`int-array`/`aset`), hosting a language |
| `macros.clj` | `unless`, `dbg`, `my-and`, `infix` | `defmacro`, quasiquote, `gensym` hygiene |
| `shapes.clj` | Areas & FizzBuzz | protocols, multimethods, records — all three dispatch styles |
| `dijkstra.clj` | Shortest paths in a road network | maps as graphs, immutable state through `loop` |
| `wordfreq.clj` | Word-frequency CLI | regex, `frequencies`, `*args*`, file *or* stdin |
| `bank.clj` | A tiny bank ledger | `atom`/`swap!`, `ex-info`/`try`/`catch` |
| `ffi-demo.clj` | Calling libm & libc | `ffi/define` — C signatures as data (needs `cc`) |
| `sqlite.clj` | A real SQLite database | binding libsqlite3 via a shim header; prepare/step/column |
| `raylib-bounce.clj` | Interactive bouncing balls | the raylib GUI binding — `:float`/struct-by-value FFI (needs raylib + a display) |
| `fractal-svg.clj` | A recursive fractal tree | recursion, trig, writing a real `.svg` file with `spit` |
| `csp-sieve.clj` | The concurrent prime sieve | core.async: one `go` block per prime, chained channels |
| `csp-chat.clj` | A broadcast chat server | sockets on the fiber event loop, `mult`/`tap` fan-out |
| `csp-http-server.clj` | A concurrent HTTP server | one `go` block per connection, no threads |
| `http-app.clj` | A small routed web app | `http.clj`'s router, path params, a shared atom |

Plus three that aren't scripts:

| File | What it is |
|---|---|
| `host.c` | Embedding clove in a C program (`#define CLOVE_NO_MAIN`); build with `make example` |
| `libhost.c` | A C host for a script built with `clove bundle --library` |
| `hello-bundle.clj` | A script meant for `clove bundle` → a standalone binary |

## Notes on the dialect

These run on clove as written, and nearly all of them would run unchanged on
JVM Clojure or babashka. Where clove differs from Clojure on purpose is listed
in the main README under
[Deliberate divergences](../README.md#deliberate-divergences-from-clojure).
