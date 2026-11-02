;; # Scripts and the REPL
;;
;; ## Running code
;;
;; ```sh
;; clove                      # REPL
;; clove script.clj a b       # run a script; *args* is ("a" "b")
;; clove -e '(+ 1 2)'         # evaluate and print
;; clove -m my.app            # require my.app and call its -main
;; echo '(+ 1 2)' | clove     # piped stdin runs as a script
;; clove watch script.clj     # rerun on every save
;; ```
;;
;; A script can name its interpreter. `#!` is a comment to the reader, so
;; this runs directly once it's executable:
;;
;; ```clojure
;; #!/usr/bin/env clove
;; (println "hello," (or (first *args*) "world"))
;; ```
;;
;; ## The REPL
;;
;; The prompt is a multi-line editor. Enter runs the form if it's complete
;; and opens a new, indented line if it isn't. ↑ brings back whole forms,
;; brackets pair themselves, Tab completes, and Ctrl-R searches history. The
;; arglists of the function you're calling show dimly after the cursor. If
;; your `~/.inputrc` says `set editing-mode vi`, you get vi mode, with
;; counts, text objects and `.`.
;;
;; Results are numbered, and `*1`, `*2`, `*3` hold the last three. A few
;; functions help you look around:
;;
;; ```clojure
;; (doc map)              ; docstring and arglists, special forms included
;; (source take-while)    ; the definition
;; (dir clojure.string)   ; a namespace's public names
;; (apropos "index")      ; names containing "index"
;; (find-doc "lazy")      ; docstrings mentioning "lazy"
;; (inspect big-map)      ; browse a value full-screen; q returns
;; ```
;;
;; After an error, `*e` holds the exception and `(pst)` prints it again.
;;
;; ## Debugging
;;
;; `dbg` prints a form and its value, then returns the value, so you can
;; wrap anything without changing what the code does. `#p` is shorthand for
;; it.
;; %%

(defn hypot [x y]
  (Math/sqrt (+ (dbg (* x x)) #p (* y y))))

(hypot 3 4)

;; Uncaught errors underline the form that failed, suggest a fix when they
;; can, and print a trace that reaches into function bodies:
;;
;; ```
;; ── ERROR  report.clj
;;
;; I don't know what `printn` refers to.
;;
;;    1 │ (printn total)
;;      │  ▔▔▔▔▔▔
;;
;; Did you mean `print`?
;;
;;   at (printn ...) line 1
;; ```
;;
;; ## Asking Claude
;;
;; A REPL line that starts with `? ` goes to Claude instead of the
;; evaluator, along with the session so far. Claude can look up docs and
;; source on its own but never runs code. When running something would
;; help, it puts the line in your prompt, and you decide whether to press
;; Enter. A bare `?` after an error asks it to explain the error. This needs
;; `curl` and an `ANTHROPIC_API_KEY`.
;;
;; ## Projects and tasks
;;
;; `clove new my-app` makes a project: `src/`, `test/`, and a `clove.edn`
;; that works like babashka's `bb.edn`. A string task is a shell command.
;;
;; ```clojure
;; {:paths ["src"]
;;  :tasks {test "clove test"
;;          ci   {:depends [test] :task (println "ok")}}}
;; ```
;;
;; `clove tasks` lists them and `clove run ci` runs one, dependencies first.
;;
;; ## Tests
;;
;; `clojure.test` is built in. `clove test` finds every `*_test.clj` under
;; the current directory, runs it, and exits 1 if anything failed. A failed
;; `(is (= ...))` prints where the two values differ.
;;
;; `clove judge` is for snapshot tests. Write `(test expr)` forms with no
;; expected value and `clove judge -a file.clj` fills in what they return.
;; Later runs fail when the value changes.
;;
;; ## Editors
;;
;; `clove nrepl` serves nREPL on port 7888 and writes `.nrepl-port`, which
;; CIDER, Calva and Conjure pick up. Evaluation and `load-file` work in all
;; of them. Completion and lookup work too, but clove doesn't implement the
;; full CIDER middleware, so some editor features won't.
;;
;; The repo has a `.clj-kondo/config.edn` tuned for clove. `clove lint` does
;; a quicker check, reading the files without running them, and `clove fmt`
;; formats with cljfmt if you have it.
