;; # Snapshot tests
;;
;; Most of the time you don't know the expected value of a test until you've
;; run the code once and looked at it. `clove judge` makes that the workflow.
;; You write the expression, judge runs it and writes the answer into your
;; file, and from then on the test fails if the answer changes.
;;
;; The idea comes from Ian Henry's [Judge](https://github.com/ianthehenry/judge)
;; for Janet, which is worth reading about even if you never write Janet.
;; clove's version is smaller, and it's a single file, `judge.cljc`, that also
;; runs under babashka.
;;
;; ## A first test
;;
;; ```clojure
;; ;; words.clj
;; (require '[clojure.string :as str]
;;          '[judge :refer [test test-error test-stdout trust]])
;;
;; (defn word-counts [s]
;;   (frequencies (str/split (str/lower-case s) #"\W+")))
;;
;; (test (word-counts "the cat and the hat"))
;; (test (count (word-counts "")) 0)
;; ```
;;
;; The first `test` has no expected value, and the second one has a guess.
;; Run judge on the file:
;;
;; ```sh
;; $ clove judge words.clj
;; words.clj:8
;; - (test (word-counts "the cat and the hat"))
;; + (test (word-counts "the cat and the hat") {"the" 2, "cat" 1, "and" 1, "hat" 1})
;;
;; words.clj:9
;; - (test (count (word-counts "")) 0)
;; + (test (count (word-counts "")) 1)
;;
;; judge: wrote words.clj.tested
;; judge: 0 passed, 2 corrections (run with -a to accept, -i to review)
;; ```
;;
;; The guess was wrong. Splitting an empty string gives `[""]`, so the empty
;; string counts as one word. That's what Clojure does too, and it's the kind
;; of thing you'd rather find out from a test than from a user. Fix
;; `word-counts`, or decide 1 is fine and accept it.
;;
;; Judge never touches your file unless you ask. A plain run writes the
;; corrected copy next to it, as `words.clj.tested`, and exits 1. You have
;; three ways to accept:
;;
;; ```sh
;; clove judge -a words.clj   # take every correction
;; clove judge -i words.clj   # go through them one by one: y, n or q
;; mv words.clj.tested words.clj
;; ```
;;
;; The corrected value goes into your source text where the old one was. The
;; rest of the file, comments and formatting included, stays exactly as you
;; wrote it.
;;
;; ## The four forms
;;
;; ```clojure
;; (test expr expected)          ; the value of expr
;; (test-error expr message)     ; the message of the error expr throws
;; (test-stdout expr printed)    ; what expr prints
;; (trust expr value)            ; filled in once, never checked again
;; ```
;;
;; Leave the last argument off any of them and judge fills it in. `trust` is
;; for values that change on every run, like a timestamp or a random number,
;; where you want a recorded example but not a failing test.
;;
;; `test-error` records clove's message, so those snapshots are specific to
;; clove. Under babashka the same error has a different message.
;;
;; ## The tests stay in the file
;;
;; Outside of judge, these four are macros that expand to nothing. Running
;; `clove words.clj` costs nothing for the tests and prints nothing for them,
;; so a library or a script can keep its tests next to the code they check.
;; The `require` is what makes that work: without it, `test` is
;; `clojure.core/test`, which takes one argument.
;;
;; ## Exit codes and babashka
;;
;; `clove judge` exits 0 when every test passes, 1 when anything needs a
;; correction, and 2 when the file fails to load. That's enough for CI or a
;; task in `clove.edn`:
;;
;; ```clojure
;; {:tasks {judge "clove judge src/words.clj"}}
;; ```
;;
;; To run the same tests with babashka, put `judge.cljc` on bb's classpath
;; or run it directly:
;;
;; ```sh
;; bb judge.cljc words.clj
;; ```
;;
;; For tests with explicit expectations and fixtures, `clojure.test` and
;; `clove test` are covered in [Scripts and the REPL](scripting.html).
;; For anything you'd otherwise check by hand at the REPL, try judge first.
