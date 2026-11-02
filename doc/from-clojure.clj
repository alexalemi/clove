;; # Coming from Clojure
;;
;; clove aims to behave like JVM Clojure, and when the two disagree, that's
;; a bug in clove unless it's listed here. We check this with a corpus of
;; 545 expressions whose output must match JVM Clojure's, and a fuzzer that
;; runs random expressions through clove and babashka every night.
;;
;; There is no JVM, so there is no Java. The classes scripts reach for first
;; are modeled in C or in clove: `java.io.File`, `String` and `Character`
;; methods, `Math`, `java.util` collections, `java.time.LocalDate` and
;; `Instant`, `clojure.java.shell/sh`. They cover the common methods, not the
;; whole class. Libraries that wrap a specific Java library (an XML parser,
;; a JDBC driver) won't run until someone ports that library.
;;
;; ## Differences you might hit
;;
;; Strings index by codepoint. `count`, `nth`, `subs` and `seq` agree with
;; what you see on screen, where the JVM counts UTF-16 code units.
;; %%

[(count "héllo") (count "𝄞") (subs "naïve" 2)]

;; Hash maps keep insertion order at any size. The JVM agrees up to 8 keys
;; and then switches to hash order. Don't rely on either: if order matters,
;; use `sorted-map`.
;; %%

(keys (zipmap (range 10) (repeat 0)))

;; Syntax quote doesn't qualify symbols. On the JVM, `` `map `` reads as
;; `clojure.core/map`; here it stays `map`. This matters only to macros that
;; inspect their own expansion.
;; %%

`(map inc xs)

;; Floating-point division by zero gives infinity or NaN, as compiled Clojure
;; does with primitive doubles. Boxed JVM math, and babashka, throw instead.
;; %%

[(/ 1.0 0) (/ 0.0 0)]

;; There is no separate `BigInteger`. `biginteger` returns a BigInt, which
;; does the same arithmetic but prints with an `N`.
;; %%

(biginteger 3)

;; ## Namespaces
;;
;; `ns`, `require` with `:as` and `:refer`, `*ns*`, `ns-publics` and
;; `^:private` all work, and multi-file libraries load. Underneath, though,
;; there is one global table of names with per-namespace aliases, and a
;; namespace is just its symbol (`(find-ns 'user)` returns `user`). Top-level
;; definitions in `user` stay visible from other namespaces, which real
;; Clojure would not allow.
;;
;; ## Smaller things
;;
;; - `catch` dispatches on exception class where clove knows it: `ex-info`,
;;   the `(IllegalArgumentException. ...)` constructors, arithmetic errors,
;;   index out of bounds, stack overflow. Other interpreter errors match
;;   any `catch` except `ExceptionInfo`, `AssertionError` and `Error`.
;; - Seqs realize in chunks of 32, as on the JVM, but `chunked-seq?` is
;;   always false.
;; - Nested syntax quote doesn't work. Auto-gensym (`x#`) does.
;; - Tagged literals stop at `#inst` and `#uuid`; `*data-readers*` isn't
;;   consulted.
;; - `tap>` calls taps on the spot instead of queuing them for a background
;;   thread.
;; - `(?i)` in a regex folds ASCII case only.
;; - `cl-format` covers the common directives but not `~<~>` justification.
;; - Host objects (`File`, `Date`, UUIDs, `reify` instances) are maps
;;   underneath, so `map?` is true of them.
;;
;; ## Things clove adds
;;
;; - Proper tail calls everywhere, not just through `recur`.
;; - `#?(:clove ...)` in reader conditionals.
;; - `(dbg x)`, or `#p x`, prints a form and its value and returns the value.
;; - The [FFI and JIT](ffi.html): call C from clove, or compile a numeric
;;   function to machine code.
;; - Coroutines (`coro/new`, `coro/yield`), which `core.async` runs on.
;;
;; The README's [divergence list](https://github.com/alexalemi/clove#deliberate-divergences-from-clojure)
;; is the full version of this page.
