# AGENTS.md

clove is a Clojure interpreter in one embeddable C99 file, `clove.c`, plus
batteries written in clove (`*.clj` at the root) and vendored libraries
(`vendor/`). Read [PLAN.md](PLAN.md) before non-trivial work: it holds the
current status, the GC invariants, open threads, and testing conventions.

## Constraints

- **`clove.c` stays a single file.** That is the product (embed with
  `-DCLOVE_NO_MAIN`, `clove bundle` compiles it into standalone binaries).
  Don't split it, and don't add dependencies beyond libc, `-lm`, `-ldl`.
- **Clojure semantics are the spec.** When behavior has a JVM answer, match
  it and check against babashka (`bb`), not intuition. Deliberate divergences
  are listed in README's "Known divergences"; add to that list rather than
  diverging silently.
- **Warnings are errors in CI** (`-Wall -Wextra -Werror`), on Linux, macOS
  and Windows/mingw. Guard platform-specific code the way the file already
  does (`#ifdef _WIN32`, `__APPLE__`).

## Finding your way in clove.c

The header comment maps the file. Jump between sections with
`grep -n '^/\* ───── ' clove.c` (major) and `grep -n '^/\* ── ' clove.c`
(minor). The prelude (core fns/macros written in clove) is C string
literals near the end; edit it as Clojure that has to stay valid C.

## Before touching allocation, the reader, or the evaluator

Read "GC invariants" in PLAN.md. The collector scans the C stack
conservatively, so locals are roots, but a collection under construction
must stay consistent at every allocation point. Missing roots show up only
under `CLOVE_GC_STRESS=1`, and most reliably under ASan at `-O1`.

## Checks

| Change | Run |
|---|---|
| anything | `make test` (suite + conformance against the JVM golden, ~20s) |
| allocation, GC, reader, evaluator, collections | `make test-full` (adds GC stress), then the ASan build below |
| numbers, printing, exceptions | `make fuzz` (differential vs babashka) |
| REPL line editor | `make test-pty` (needs python3 + pexpect) |

ASan, as CI runs it. The binary must be named `./clove`, because the suite
re-invokes `<exe-dir>/clove` for its subprocess tests:

```sh
cc -g -O1 -fsanitize=address,undefined -fno-sanitize-recover=undefined -o clove clove.c -lm -ldl
ASAN_OPTIONS=detect_leaks=0:detect_stack_use_after_return=0 ./clove tests.clj
ASAN_OPTIONS=detect_leaks=0:detect_stack_use_after_return=0 CLOVE_GC_STRESS=1 ./clove tests.clj
```

- The verdict is clove's exit status plus the last line, `tests complete: N
  passed, 0 failed`. Never grep the output for "error" or "FAIL"; tests print
  those words on purpose.
- Don't run two copies of `tests.clj` at once on one machine. The networking
  tests bind fixed localhost ports, and the second run aborts with "port in
  use".
- Stress mode skips a few sections, so its assertion count is lower than a
  normal run's.

## Conventions

- Every bug fix gets a regression assertion in `tests.clj`. Use `pr-str` when
  the type matters: `=` cannot distinguish `2` from `2N`.
- Behavior with a JVM answer also gets a line in `fuzz/conformance.txt`.
  Regenerate the golden with
  `bb -f fuzz/runner.clj -- fuzz/conformance.txt > fuzz/conformance_expected.txt`
  and check that the diff contains only your new lines.
- User-visible changes go in `CHANGELOG.md` under "Unreleased". Feature
  surface goes in README, and status or open threads go in PLAN.md.
  HISTORY.md and released CHANGELOG entries are a historical record: leave
  them alone (they keep the old `cljc` name).
- An integer position (index, count, key) reads its value with `int_value`
  or `as_int`, not `tag == CLOVE_INT`. A BigInt like `1N` is its own type at
  any magnitude (see "BigInt contagion" in PLAN.md).
- The built binary (`clove`) is gitignored. Build it in each fresh checkout
  with `make`.
