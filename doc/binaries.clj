;; # Standalone binaries
;;
;; `clove bundle` compiles a script and the interpreter into one executable.
;; It needs no `clove` installed and no files beside it, and it comes out
;; around 500 KB.
;;
;; ```sh
;; clove bundle wordcount.clj wordcount
;; ./wordcount README.md
;; ```
;;
;; The bundle includes everything the script `require`s or `load-file`s,
;; batteries and vendored libraries too. It isn't compiled to native code:
;; the script still runs on the interpreter inside. Arguments arrive in
;; `*args*`, and if the script defines `-main`, the bundle calls it with
;; them. `clove bundle -m my.app out` bundles a namespace and uses its
;; `-main`.
;;
;; ## Other machines
;;
;; A bundle is a native program for the machine that built it. To hand one
;; to someone else, match their platform:
;;
;; | for | command | needs |
;; |---|---|---|
;; | your own Linux | `clove bundle app.clj app` | |
;; | any Linux on your architecture | `clove bundle --static app.clj app` | a static libc (glibc's works, musl is better) |
;; | Linux on ARM64 | `clove bundle --cc=aarch64-linux-gnu-gcc --static app.clj app` | `gcc-aarch64-linux-gnu` |
;; | Windows | `clove bundle --windows app.clj app.exe` | `x86_64-w64-mingw32-gcc` |
;;
;; `--static` avoids the "GLIBC_2.38 not found" error on older systems.
;; `--cc=`, `--cflags=` and `--libs=` hand anything else to the compiler, so
;; `zig cc` works as a cross compiler too.
;;
;; ## Shared libraries
;;
;; `--library` goes the other way, building your script into a `.so`,
;; `.dylib` or `.dll` that any language with a C FFI can load:
;;
;; ```sh
;; clove bundle --library greet.clj libgreet.so   # also writes libgreet.so.h
;; cc -O2 host.c ./libgreet.so -o host
;; ```
;;
;; The library exports three functions:
;;
;; ```c
;; int         clove_lib_init(void);              /* runs greet.clj; 0 on success */
;; const char *clove_lib_eval(const char *src);   /* pr-str of the result, or NULL */
;; const char *clove_lib_last_error(void);
;; ```
;;
;; State persists between calls, and an error leaves the interpreter usable.
;; A returned string is valid until the next call. The rules from
;; [Embedding in C](embedding.html) apply: one interpreter per process, used
;; from one thread. `examples/libhost.c` in the repo is a working host.
