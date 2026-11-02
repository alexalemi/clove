;; # Install
;;
;; clove builds from source in a few seconds. You need a C compiler (`cc`,
;; gcc or clang) and `make`. Nothing else: the interpreter links against libc,
;; `libm` and `libdl`.
;;
;; ## One line
;;
;; ```sh
;; curl -fsSL https://raw.githubusercontent.com/alexalemi/clove/main/install.sh | sh
;; ```
;;
;; The script downloads the source of the newest tagged release, builds it,
;; and installs `clove` to `~/.local/bin`, with its libraries in
;; `~/.local/share/clove`. Make sure `~/.local/bin` is on your `PATH`.
;;
;; Options go after `sh -s --`:
;;
;; ```sh
;; curl -fsSL .../install.sh | sh -s -- --version v0.9.0   # a specific release
;; curl -fsSL .../install.sh | sh -s -- --version main     # the latest commit
;; curl -fsSL .../install.sh | sh -s -- --prefix /usr/local
;; curl -fsSL .../install.sh | sh -s -- --test             # run the test suite first
;; ```
;;
;; Read the script before piping it to a shell if you like; it's about 120 lines of
;; POSIX sh.
;;
;; ## From a checkout
;;
;; ```sh
;; git clone https://github.com/alexalemi/clove
;; cd clove
;; make            # builds ./clove
;; make test       # about 20 seconds
;; ./install.sh    # or: PREFIX=/usr/local ./install.sh
;; ```
;;
;; An uninstalled `./clove` works too, from any directory. It finds its
;; libraries next to the binary.
;;
;; ## Check it
;;
;; ```sh
;; $ clove -e '(+ 1 2)'
;; 3
;; $ clove doctor
;; ```
;;
;; `clove doctor` prints the load path and where it found the batteries. Run
;; it when a `require` can't find something.
;;
;; ## Platforms
;;
;; Linux is the reference platform. macOS (arm64 and x86_64) runs everything.
;; Windows builds with mingw-w64 and runs the core language, the REPL and
;; sockets, but not coroutines, `core.async`, nREPL, the FFI or the JIT.
;;
;; ## Uninstall
;;
;; ```sh
;; rm ~/.local/bin/clove
;; rm -r ~/.local/share/clove
;; ```
;;
;; From a checkout, `make uninstall PREFIX=...` does the same.
