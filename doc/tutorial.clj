;; # Tutorial
;;
;; This page assumes you've programmed before but maybe not in a Lisp. Every
;; code block runs: click one, change it, and press shift+enter. Or start
;; `clove` in a terminal and type along.
;;
;; ## Calling things
;;
;; Code is made of lists. The first item is the function and the rest are its
;; arguments, so `f(x, y)` elsewhere is `(f x y)` here. Operators are
;; ordinary functions and take any number of arguments.
;; %%

(+ 1 2 3 4)

;; %%
(str "two plus two is " (+ 2 2))

;; Integer division doesn't truncate. It gives you a ratio, which stays exact
;; until you mix in a float.
;; %%

[(/ 7 2) (/ 7 2.0) (+ 1/3 1/6)]

;; ## Naming things
;;
;; `def` names a value and `defn` names a function. A docstring can go
;; before the argument vector.
;; %%

(def tax-rate 8/100)

(defn with-tax
  "price plus sales tax, rounded to the cent"
  [price]
  (/ (Math/round (* 100.0 price (+ 1 tax-rate))) 100.0))

(with-tax 19.99)

;; `let` names things locally, for the length of its body.
;; %%

(let [w 3, h 4]
  (Math/sqrt (+ (* w w) (* h h))))

;; ## Data
;;
;; There are four collection literals: lists `()`, vectors `[]`, maps `{}`
;; and sets `#{}`. Keywords like `:name` are the usual map keys, and a
;; keyword is also a function that looks itself up in a map.
;; %%

(def book {:title "Dune" :author "Frank Herbert" :year 1965})

[(:title book) (get book :pages 412) (count book)]

;; Collections are immutable. `assoc` and `conj` return a new collection and
;; leave the old one alone; the two share most of their structure, so this
;; is cheap.
;; %%

(def reprint (assoc book :year 2005))

[(:year book) (:year reprint)]

;; ## Sequences
;;
;; Most of the library works on sequences: `map`, `filter`, `reduce`, and a
;; few hundred more. The threading macro `->>` passes each result as the last
;; argument of the next call, which reads top to bottom.
;; %%

(def library
  [{:title "Dune" :year 1965 :pages 412}
   {:title "Neuromancer" :year 1984 :pages 271}
   {:title "Hyperion" :year 1989 :pages 482}
   {:title "Foundation" :year 1951 :pages 255}])

(->> library
     (filter #(> (:pages %) 300))
     (map :title))

;; A sequence of maps shows up as a table here.
;; %%

(sort-by :year library)

;; Sequences can be lazy, even infinite. Only the part you ask for gets
;; computed.
;; %%

(->> (range)
     (map #(* % %))
     (filter odd?)
     (take 5))

;; ## Taking things apart
;;
;; Wherever you bind a name you can destructure: pull fields out of a map
;; with `:keys`, or items out of a vector by position.
;; %%

(defn describe [{:keys [title year]}]
  (str title " (" year ")"))

(map describe library)

;; %%
(let [[first-book & others] library]
  (str (:title first-book) " and " (count others) " more"))

;; ## Changing things
;;
;; Values don't change, but an atom holds a value that can be swapped for a
;; new one. `swap!` applies a function to the current value.
;; %%

(def visits (atom {}))

(defn visit! [page]
  (swap! visits update page (fnil inc 0)))

(visit! "/home")
(visit! "/about")
(visit! "/home")

@visits

;; ## Macros
;;
;; Code is data, so a macro is a function from code to code, run before
;; evaluation. Here is `unless`, which Clojure doesn't have:
;; %%

(defmacro unless [test & body]
  `(if ~test nil (do ~@body)))

(unless (empty? library) "we have books")

;; `macroexpand` shows what a macro turns into.
;; %%

(macroexpand '(unless done? (println "still going")))

;; ## Errors
;;
;; `throw` takes an `ex-info` with a message and a map of data, and
;; `try`/`catch` handles it.
;; %%

(defn checkout [book]
  (if (:on-loan book)
    (throw (ex-info "already on loan" {:title (:title book)}))
    (assoc book :on-loan true)))

(try
  (checkout (checkout (first library)))
  (catch Exception e
    [(ex-message e) (ex-data e)]))

;; ## Scripts
;;
;; Put code in a file and run it:
;;
;; ```sh
;; $ cat hello.clj
;; (println "hello," (or (first *args*) "world"))
;; $ clove hello.clj there
;; hello, there
;; ```
;;
;; [Scripts and the REPL](scripting.html) picks up from here. Since clove
;; is Clojure, any Clojure book or tutorial works for the language itself;
;; [Clojure for the Brave and True](https://www.braveclojure.com) is a good
;; one, free online.
