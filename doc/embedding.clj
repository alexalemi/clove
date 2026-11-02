;; # Embedding in C
;;
;; clove is one C file, and embedding it means including that file. Define
;; `CLOVE_NO_MAIN` and `#include "clove.c"` in one of your translation
;; units. There's no library to link and no header to keep in sync, and
;; your code sees the interpreter's own value constructors.
;;
;; Say your program wants its pricing rules in a file that users can edit:
;;
;; ```clojure
;; ;; rules.clj
;; (defn discount [{:keys [total items]}]
;;   (cond
;;     (> total 100) 10
;;     (>= items 3) 5
;;     :else 0))
;; ```
;;
;; The C side loads the file, passes in a map, and reads back a number:
;;
;; ```c
;; #define CLOVE_NO_MAIN
;; #include "clove.c"
;; #include <time.h>
;;
;; /* (now) gives scripts the time, from C */
;; static Clove *native_now(CloveEnv *env, Clove **argv, int nargs) {
;;     (void)env; (void)argv;
;;     if (nargs != 0) clove_error("now takes no arguments");
;;     return mk_int((int64_t)time(NULL));
;; }
;;
;; int main(int argc, char **argv) {
;;     (void)argv;
;;     clove_set_stack_base(&argc);
;;     CloveEnv *env = clove_new_env();
;;     clove_define_native(env, "now", native_now);
;;
;;     clove_eval_string(env, "(load-file \"rules.clj\")");
;;     if (clove_eval_errored) return 1;
;;
;;     Clove *d = clove_eval_string(env, "(discount {:total 120 :items 3})");
;;     if (clove_eval_errored) return 1;
;;     printf("discount: %lld%%\n", (long long)as_int(d, "discount"));
;;     return 0;
;; }
;; ```
;;
;; ```sh
;; $ cc -O2 -o shop shop.c -lm -ldl
;; $ ./shop
;; discount: 10%
;; ```
;;
;; ## The API
;;
;; | | |
;; |---|---|
;; | `clove_set_stack_base(void *p)` | call first, with the address of a local in `main` |
;; | `clove_new_env()` | a fresh interpreter environment with the core library loaded |
;; | `clove_eval_string(env, src)` | read and evaluate every form in `src`; returns the last value |
;; | `clove_eval_errored` | true if the last `clove_eval_string` threw; the error was printed to stderr |
;; | `clove_define_native(env, name, fn)` | make a C function callable from clove |
;; | `clove_print(v)` | print a value as `pr` would |
;;
;; A native has the signature `Clove *fn(CloveEnv *env, Clove **argv, int nargs)`.
;; To build values, use `mk_int`, `mk_double`, `mk_str(s, len)`,
;; `mk_kw(intern("name", 4))` (keywords take an interned name), `mk_vector`,
;; and `mk_map` with `map_assoc`. To read arguments, use
;; `as_int`, `as_str` and friends, which throw a clove error with your
;; function's name when the type is wrong. `clove_error(fmt, ...)` throws
;; from C, and scripts can catch it. `examples/host.c` in the repo shows
;; most of these.
;;
;; ## The rules
;;
;; The garbage collector finds live values by scanning the C stack. That's
;; why your natives don't have to register anything, and it's also where the
;; rules come from.
;;
;; - Call `clove_set_stack_base` before evaluating anything, from a frame
;;   that outlives every call into clove. `main` is the usual place.
;; - A `Clove *` in a local variable is safe. One stored in a global or on
;;   the heap is not: the collector can't see it and may free it during the
;;   next evaluation. To keep a value, `def` it from clove and look it up
;;   again when you need it.
;; - One interpreter per process, used from one thread. The interpreter
;;   keeps global state.
;;
;; ## Going the other way
;;
;; To call clove from a language that isn't C, or to ship your script
;; without its source, `clove bundle --library` builds a shared library with
;; a three-function API. See [Standalone binaries](binaries.html).
