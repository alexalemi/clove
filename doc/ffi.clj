;; # FFI and the JIT
;;
;; Both work by writing a little C, compiling it with the system's `cc` into
;; a shared library, and loading that with `dlopen`. You need a C compiler at
;; run time. Compiled modules are cached by a hash of their source, so only
;; the first run pays for the compile. Neither works on Windows or in the
;; browser.
;;
;; ## Calling C
;;
;; `ffi/define` takes C signatures as data and defines a clove function for
;; each one.
;;
;; ```clojure
;; (ffi/define [[:double cos [:double]]
;;              [:int abs [:int]]]
;;             {:headers ["math.h" "stdlib.h"] :libs "-lm"})
;;
;; (cos 0.0)  ;=> 1.0
;; (abs -5)   ;=> 5
;; ```
;;
;; The types are `:int`, `:double`, `:float`, `:string`, `:pointer` and
;; `:void`, plus structs by value once you've declared them with `:structs`.
;; A pointer is an integer, and 0 is NULL. `:libs` goes to the
;; compiler's command line, so `"-lsqlite3"` links SQLite.
;;
;; `ffi/defstruct` writes the constructor and accessors for a C struct:
;;
;; ```clojure
;; (ffi/defstruct timeval [[:int tv_sec] [:int tv_usec]] {:headers ["sys/time.h"]})
;; (ffi/define [[:int gettimeofday [:pointer :pointer]] [:void free [:pointer]]]
;;             {:headers ["sys/time.h" "stdlib.h"]})
;;
;; (def tv (make-timeval))
;; (gettimeofday tv 0)
;; (timeval-tv_sec tv)   ;=> 1793642490
;; (free tv)
;; ```
;;
;; The repo has bigger examples. `libc.clj` binds a chunk of libc,
;; `examples/sqlite.clj` talks to SQLite, and `raylib.clj` has enough of
;; raylib to draw a bouncing ball (`examples/raylib-bounce.clj`).
;;
;; ## Compiling hot functions
;;
;; `jit.clj` compiles a numeric subset of clove to C: `if`, `let`, `loop` and
;; `recur`, calls to itself, arithmetic and comparisons, `Math/sqrt` and
;; friends. Define the function with `jit/defn`, then compile it, and the
;; name now points at machine code.
;;
;; ```clojure
;; (load-file "jit.clj")
;;
;; (jit/defn fib [n]
;;   (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
;;
;; (time (fib 32))      ; "Elapsed time: 780 msecs"
;; (jit/compile! 'fib)
;; (time (fib 32))      ; "Elapsed time: 12 msecs"
;; ```
;;
;; Numbers are `long long` unless you hint `^double`, on parameters or on the
;; function name for its return type:
;;
;; ```clojure
;; (jit/defn ^double dist [^double x ^double y]
;;   (Math/sqrt (+ (* x x) (* y y))))
;; (jit/compile! 'dist)
;; (dist 3.0 4.0)  ;=> 5.0
;; ```
;;
;; Anything outside the subset fails to compile, and the interpreted
;; function stays in place:
;;
;; ```clojure
;; (jit/defn shout [s] (str s "!"))
;; (jit/compile! 'shout)
;; ;; jit: unsupported form (str s "!")
;; ```
