;; peg.clj — Janet-style Parsing Expression Grammars for cljc.
;; Load with: (require '[peg :as peg])
;;
;;   (peg/match '(* (<- :d+) "-" (<- :d+)) "12-34")        => ["12" "34"]
;;   (peg/match '{:main (* :pair (any (* "," :pair)))
;;                :pair (group (* (<- :w+) "=" (number :d+)))} "a=1,b=2")
;;                                                        => [["a" 1] ["b" 2]]
;;   (peg/find-all "an" "banana")                          => [1 3]
;;   (peg/replace-all :d+ (fn [m] (str "<" m ">")) "a1b22")      => "a<1>b<22>"
;;
;; Patterns are plain data, so quote them: '(* "a" :d+). Vectors work like
;; lists; '"foo" inside a quoted pattern reads as (quote "foo") = a capture.
;; Semantics follow Janet's peg.c (a PEG: ordered choice, no backtracking
;; into a succeeded alternative, captures unwound on failure) with one
;; deliberate difference: positions and (<- 1) are codepoints, not bytes.
;;
;; Primitives:  "lit"  \c  n (n chars; -n: fewer than n remain)  :rule
;;              (range "az" "AZ")  (set "abc")  {:main ... :rule ...}
;; Combinators: (* a b ..) (sequence ..) (+ a b ..) (choice ..) (! p) (not p)
;;              (any p) (some p) (opt p) (? p) (between lo hi p) (at-least n p)
;;              (at-most n p) (repeat n p) (n p) (if c p) (if-not c p)
;;              (look off p) (> off p) (to p) (thru p) (sub window p)
;;              (split sep p) (backmatch ?tag)
;; Captures:    (<- p ?tag) (capture p ?tag) (quote p ?tag) (group p ?tag)
;;              (replace p subst ?tag) (/ p subst ?tag) (cmt p f ?tag)
;;              (constant k ?tag) (argument n ?tag) (position ?tag) ($ ?tag)
;;              (line ?tag) (column ?tag) (accumulate p ?tag) (% p ?tag)
;;              (number p ?base ?tag) (drop p) (backref tag) (-> tag)
;;              (unref p ?tag) (nth i p ?tag) (only-tags p) (error ?p)
;; API:         (compile patt) (match peg text ?start & args)
;;              (find peg text ?start & args) (find-all peg text ?start & args)
;;              (replace peg subst text ?start & args) (replace-all ...)

;; ── match state ──────────────────────────────────────────────────────────
;; A compiled pattern is (fn [ctx i]) → end index or nil. ctx holds the text,
;; the end bound (shrunk by `sub`), the extra match arguments, and two atoms:
;; the capture stack and the tag table. Failure handlers (choice, loops, not,
;; ...) snapshot both with `mark` and unwind with `unwind!` — Janet's
;; cap_save/cap_load.

(defn- mark [ctx] [(count @(:caps ctx)) @(:tags ctx)])

(defn- unwind! [ctx [n tags]]
  (let [c (:caps ctx)]
    (when (> (count @c) n)
      (swap! c (fn [v] (loop [v v] (if (> (count v) n) (recur (pop v)) v)))))
    (reset! (:tags ctx) tags)))

(defn- push! [ctx tag v]
  (swap! (:caps ctx) conj v)
  (when tag (swap! (:tags ctx) assoc tag v))
  v)

;; captures pushed since mark n, removed from the stack
(defn- take-caps! [ctx n]
  (let [c (:caps ctx) v @c]
    (swap! c (fn [v] (loop [v v] (if (> (count v) n) (recur (pop v)) v))))
    (subvec v n)))

(defn- line-col [s i]
  (loop [k 0 line 1 col 1]
    (if (>= k i)
      [line col]
      (if (= \newline (nth s k))
        (recur (inc k) (inc line) 1)
        (recur (inc k) line (inc col))))))

(defn- peg-error [msg & [data]]
  (throw (ex-info msg (merge {:type :peg/error} data))))

;; ── compiler ─────────────────────────────────────────────────────────────
;; env: {:rules (atom {kw cell})} where cell is an atom holding the compiled
;; rule (nil until defined — a forward reference). :all collects every cell so
;; unresolved references are reported once compilation finishes.

(declare compile-patt)

(defn- rule-cell [env k]
  (or (get @(:rules env) k)
      (let [cell (atom nil)]
        (swap! (:rules env) assoc k cell)
        (swap! (:all env) conj [k cell])
        cell)))

(defn- lit-matcher [lit]
  (let [n (count lit) s* (str lit)]
    (fn [ctx i]
      (when (and (<= (+ i n) (:len ctx)) (cljc/str-starts-at? (:s ctx) s* i))
        (+ i n)))))

(defn- count-matcher [n]
  (if (neg? n)
    (fn [ctx i] (when (< (- (:len ctx) i) (- n)) i))
    (fn [ctx i] (when (<= (+ i n) (:len ctx)) (+ i n)))))

(defn- char-matcher [pred]
  (fn [ctx i]
    (when (and (< i (:len ctx)) (pred (nth (:s ctx) i))) (inc i))))

(defn- range-matcher [rs]
  (doseq [r rs]
    (when-not (and (string? r) (= 2 (count r)))
      (peg-error (str "range: expected a two-char string, got " (pr-str r)))))
  (let [pairs (mapv (fn [r] [(int (nth r 0)) (int (nth r 1))]) rs)]
    (char-matcher (fn [c] (let [c (int c)]
                            (loop [ps pairs]
                              (when (seq ps)
                                (let [[lo hi] (first ps)]
                                  (or (<= lo c hi) (recur (rest ps)))))))))))

(defn- seq-matcher [ms]
  (case (count ms)
    0 (fn [ctx i] i)
    1 (first ms)
    (fn [ctx i]
      (loop [ms ms i i]
        (if (empty? ms)
          i
          (when-let [j ((first ms) ctx i)]
            (recur (rest ms) j)))))))

(defn- choice-matcher [ms]
  (fn [ctx i]
    (let [m (mark ctx)]
      (loop [ms ms]
        (when (seq ms)
          (or ((first ms) ctx i)
              (do (unwind! ctx m) (recur (rest ms)))))))))

;; Janet's RULE_BETWEEN: iterate while under hi; an iteration that fails OR
;; makes no progress is unwound and ends the loop.
(defn- between-matcher [lo hi p]
  (fn [ctx i]
    (let [m0 (mark ctx)]
      (loop [i i n 0]
        (if (and hi (>= n hi))
          (if (>= n lo) i (do (unwind! ctx m0) nil))
          (let [m (mark ctx) j (p ctx i)]
            (if (or (nil? j) (= j i))
              (do (unwind! ctx m)
                  (if (>= n lo) i (do (unwind! ctx m0) nil)))
              (recur j (inc n)))))))))

;; exact repeat: no progress check (Janet's RULE_REPEAT)
(defn- repeat-matcher [n p]
  (fn [ctx i]
    (loop [i i k 0]
      (if (= k n) i (when-let [j (p ctx i)] (recur j (inc k)))))))

(defn- not-matcher [p]
  (fn [ctx i]
    (let [m (mark ctx) r (p ctx i)]
      (unwind! ctx m)
      (when-not r i))))

;; scan forward for p: [k end mark] at the first position where it matches
(defn- scan [ctx i p]
  (loop [k i]
    (when (<= k (:len ctx))
      (let [m (mark ctx) e (p ctx k)]
        (if e
          [k e m]
          (do (unwind! ctx m) (recur (inc k))))))))

(defn- capture-text [ctx i j] (subs (:s ctx) i j))

(defn- parse-number [text base]
  (if base
    (let [neg (str/starts-with? text "-")
          digits (if neg (subs text 1) text)]
      (when (seq digits)
        (loop [k 0 acc 0]
          (if (= k (count digits))
            (if neg (- acc) acc)
            (let [d (Character/digit (nth digits k) base)]
              (when (and d (>= d 0)) (recur (inc k) (+ (* acc base) d))))))))
    (or (parse-long text) (parse-double text))))

(defn- arity! [head args lo hi]
  (let [n (count args)]
    (when (or (< n lo) (and hi (> n hi)))
      (peg-error (str "(" head " ...): expected " lo (if (= lo hi) "" (str "-" (or hi "n")))
                      " arguments, got " n)))))

(defn- compile-form [env head args]
  (let [c (fn [p] (compile-patt env p))
        h (name head)
        int-arg (fn [x] (if (integer? x) x (peg-error (str "(" h " ...): expected an integer, got " (pr-str x)))))]
    (case h
      ("*" "sequence") (seq-matcher (mapv c args))
      ("+" "choice")   (choice-matcher (mapv c args))
      ("!" "not")      (do (arity! h args 1 1) (not-matcher (c (first args))))
      "any"            (do (arity! h args 1 1) (between-matcher 0 nil (c (first args))))
      "some"           (do (arity! h args 1 1) (between-matcher 1 nil (c (first args))))
      ("opt" "?")      (do (arity! h args 1 1) (between-matcher 0 1 (c (first args))))
      "between"        (do (arity! h args 3 3)
                           (between-matcher (int-arg (first args)) (int-arg (second args)) (c (nth args 2))))
      "at-least"       (do (arity! h args 2 2) (between-matcher (int-arg (first args)) nil (c (second args))))
      "at-most"        (do (arity! h args 2 2) (between-matcher 0 (int-arg (first args)) (c (second args))))
      "repeat"         (do (arity! h args 2 2) (repeat-matcher (int-arg (first args)) (c (second args))))
      "if"             (do (arity! h args 2 2)
                           (let [cnd (c (first args)) p (c (second args))]
                             (fn [ctx i] (when (cnd ctx i) (p ctx i)))))
      "if-not"         (do (arity! h args 2 2)
                           (let [cnd (not-matcher (c (first args))) p (c (second args))]
                             (fn [ctx i] (when (cnd ctx i) (p ctx i)))))
      ("look" ">")     (do (arity! h args 2 2)
                           (let [off (int-arg (first args)) p (c (second args))]
                             (fn [ctx i]
                               (let [j (+ i off)]
                                 (when (and (<= 0 j) (<= j (:len ctx)) (p ctx j)) i)))))
      "to"             (do (arity! h args 1 1)
                           (let [p (c (first args))]
                             (fn [ctx i] (when-let [[k _ m] (scan ctx i p)] (unwind! ctx m) k))))
      "thru"           (do (arity! h args 1 1)
                           (let [p (c (first args))]
                             (fn [ctx i] (when-let [[_ e] (scan ctx i p)] e))))
      "sub"            (do (arity! h args 2 2)
                           (let [w (c (first args)) p (c (second args))]
                             (fn [ctx i]
                               (when-let [e (w ctx i)]
                                 (when (p (assoc ctx :len e) i) e)))))
      "split"          (do (arity! h args 2 2)
                           (let [sep (c (first args)) p (c (second args))]
                             (fn [ctx i]
                               (let [len (:len ctx)]
                                 (loop [i i]
                                   (let [[k e] (or (scan ctx i sep) [len len])]
                                     (when (p (assoc ctx :len k) i)
                                       (if (< k len)
                                         (recur (max e (inc k)))
                                         len))))))))
      "backmatch"      (do (arity! h args 0 1)
                           (let [tag (first args)]
                             (fn [ctx i]
                               (let [v (if tag (get @(:tags ctx) tag) (peek @(:caps ctx)))]
                                 (when (string? v) ((lit-matcher v) ctx i))))))
      ;; ── captures ──
      ("<-" "capture" "quote")
      (do (arity! h args 1 2)
          (let [p (c (first args)) tag (second args)]
            (fn [ctx i] (when-let [j (p ctx i)] (push! ctx tag (capture-text ctx i j)) j))))
      "group"
      (do (arity! h args 1 2)
          (let [p (c (first args)) tag (second args)]
            (fn [ctx i]
              (let [n (count @(:caps ctx))]
                (when-let [j (p ctx i)] (push! ctx tag (take-caps! ctx n)) j)))))
      ("replace" "/")
      (do (arity! h args 2 3)
          (let [p (c (first args)) subst (second args) tag (nth args 2 nil)]
            (fn [ctx i]
              (let [n (count @(:caps ctx))]
                (when-let [j (p ctx i)]
                  (let [caps (take-caps! ctx n)]
                    (push! ctx tag (cond (fn? subst) (apply subst caps)
                                         (map? subst) (get subst (peek caps))
                                         :else subst))
                    j))))))
      "cmt"
      (do (arity! h args 2 3)
          (let [p (c (first args)) f (second args) tag (nth args 2 nil)]
            (when-not (fn? f) (peg-error "(cmt ...): expected a function"))
            (fn [ctx i]
              (let [n (count @(:caps ctx)) m (mark ctx)]
                (when-let [j (p ctx i)]
                  (let [r (apply f (take-caps! ctx n))]
                    (if (or (nil? r) (false? r))     ; Janet: only nil/false fail
                      (do (unwind! ctx m) nil)
                      (do (push! ctx tag r) j))))))))
      "constant"
      (do (arity! h args 1 2)
          (let [k (first args) tag (second args)]
            (fn [ctx i] (push! ctx tag k) i)))
      "argument"
      (do (arity! h args 1 2)
          (let [n (int-arg (first args)) tag (second args)]
            (fn [ctx i] (push! ctx tag (get (:args ctx) n)) i)))
      ("position" "$")
      (do (arity! h args 0 1)
          (let [tag (first args)] (fn [ctx i] (push! ctx tag i) i)))
      "line"
      (do (arity! h args 0 1)
          (let [tag (first args)] (fn [ctx i] (push! ctx tag (first (line-col (:s ctx) i))) i)))
      "column"
      (do (arity! h args 0 1)
          (let [tag (first args)] (fn [ctx i] (push! ctx tag (second (line-col (:s ctx) i))) i)))
      ("accumulate" "%")
      (do (arity! h args 1 2)
          (let [p (c (first args)) tag (second args)]
            (fn [ctx i]
              (let [n (count @(:caps ctx))]
                (when-let [j (p ctx i)]
                  (push! ctx tag (apply str (take-caps! ctx n)))
                  j)))))
      "number"
      (do (arity! h args 1 3)
          (let [p (c (first args)) base (second args) tag (nth args 2 nil)]
            (fn [ctx i]
              (let [m (mark ctx)]
                (when-let [j (p ctx i)]
                  (if-let [v (parse-number (capture-text ctx i j) base)]
                    (do (push! ctx tag v) j)
                    (do (unwind! ctx m) nil)))))))
      "drop"
      (do (arity! h args 1 1)
          (let [p (c (first args))]
            (fn [ctx i] (let [m (mark ctx)] (when-let [j (p ctx i)] (unwind! ctx m) j)))))
      ("backref" "->")
      (do (arity! h args 1 2)
          (let [tag (first args) tag2 (second args)]
            (fn [ctx i]
              (let [tags @(:tags ctx)]
                (when (contains? tags tag) (push! ctx tag2 (get tags tag)) i)))))
      "unref"
      (do (arity! h args 1 2)
          (let [p (c (first args)) tag (second args)]
            (fn [ctx i]
              (let [before @(:tags ctx)]
                (when-let [j (p ctx i)]
                  (if tag
                    (swap! (:tags ctx) (fn [t] (if (contains? before tag)
                                                 (assoc t tag (get before tag))
                                                 (dissoc t tag))))
                    (reset! (:tags ctx) before))
                  j)))))
      "nth"
      (do (arity! h args 2 3)
          (let [idx (int-arg (first args)) p (c (second args)) tag (nth args 2 nil)]
            (fn [ctx i]
              (let [n (count @(:caps ctx)) m (mark ctx)]
                (when-let [j (p ctx i)]
                  (let [caps (take-caps! ctx n)]
                    (if (< -1 idx (count caps))
                      (do (push! ctx tag (nth caps idx)) j)
                      (do (unwind! ctx m) nil))))))))
      "only-tags"
      (do (arity! h args 1 1)
          (let [p (c (first args))]
            (fn [ctx i]
              (let [n (count @(:caps ctx))]
                (when-let [j (p ctx i)] (take-caps! ctx n) j)))))
      "error"
      (do (arity! h args 0 1)
          (let [p (if (seq args) (c (first args)) (fn [ctx i] i))]
            (fn [ctx i]
              (let [n (count @(:caps ctx))]
                (when-let [j (p ctx i)]
                  (let [caps (take-caps! ctx n)
                        [line col] (line-col (:s ctx) j)]
                    (peg-error (if (seq caps)
                                 (str (peek caps))
                                 (str "match error at line " line ", column " col))
                               {:position j :line line :column col
                                :capture (peek caps)})))))))
      (peg-error (str "unknown peg combinator: " h)))))

(defn- compile-grammar [env g]
  (when-not (contains? g :main) (peg-error "grammar: no :main rule"))
  (let [env (assoc env :rules (atom @(:rules env)))]
    ;; fresh cells for every rule shadow any outer definition
    (doseq [k (keys g)]
      (let [cell (atom nil)]
        (swap! (:rules env) assoc k cell)
        (swap! (:all env) conj [k cell])))
    (doseq [[k p] g] (reset! (get @(:rules env) k) (compile-patt env p)))
    (compile-patt env :main)))

(defn- compile-patt [env p]
  (cond
    (string? p)  (lit-matcher p)
    (char? p)    (lit-matcher (str p))
    (integer? p) (count-matcher p)
    (keyword? p) (let [cell (rule-cell env p)]
                   (fn [ctx i] (@cell ctx i)))
    (map? p)     (compile-grammar env p)
    (and (sequential? p) (seq p))
    (let [[h & args] p]
      (cond
        (symbol? h)
        (case (name h)
          "range" (range-matcher args)
          "set"   (do (arity! "set" args 1 1)
                      (let [cs (set (str (first args)))] (char-matcher cs)))
          (compile-form env h args))
        (integer? h) (do (arity! "n" args 1 1) (repeat-matcher h (compile-patt env (first args))))
        :else (peg-error (str "bad pattern head: " (pr-str h)))))
    :else (peg-error (str "bad pattern: " (pr-str p)))))

(def ^:private default-grammar
  '{:d (range "09") :a (range "az" "AZ") :w (range "az" "AZ" "09")
    :s (set " \t\r\n\f") :h (range "09" "af" "AF")
    :D (if-not :d 1) :A (if-not :a 1) :W (if-not :w 1) :S (if-not :s 1) :H (if-not :h 1)
    :d+ (some :d) :a+ (some :a) :w+ (some :w) :s+ (some :s) :h+ (some :h)
    :d* (any :d) :a* (any :a) :w* (any :w) :s* (any :s) :h* (any :h)})

;; ── public API ───────────────────────────────────────────────────────────

(defn compile
  "Compile a pattern (or grammar map) into a matcher. The result is a fn:
   (m text ?start & args) => captures vector or nil, usable anywhere a raw
   pattern is (match/find/replace accept both)."
  [patt]
  (let [env {:rules (atom {}) :all (atom [])}]
    (doseq [[k p] default-grammar] (reset! (rule-cell env k) (compile-patt env p)))
    (let [m (compile-patt env patt)]
      (doseq [[k cell] @(:all env)]
        (when (nil? @cell) (peg-error (str "grammar error in " k ", unknown rule"))))
      (let [run (fn [text start args]
                  (let [ctx {:s text :len (count text) :args (vec args)
                             :caps (atom []) :tags (atom {})}]
                    (when-let [e (m ctx start)] [e @(:caps ctx)])))]
        (with-meta (fn [text & [start & args]]
                     (second (run text (or start 0) args)))
          {:peg/run run})))))

(def ^:private cache (atom {}))

(defn- runner [peg]
  (or (:peg/run (meta peg))
      (:peg/run (meta (or (get @cache peg)
                          (let [c (compile peg)]
                            (swap! cache (fn [m] (if (> (count m) 64) {peg c} (assoc m peg c))))
                            c))))))

(defn match
  "Match peg (pattern, grammar, or compiled) at start (default 0) of text.
   Returns the capture vector (possibly empty) or nil. Extra args are
   available to (argument n)."
  [peg text & [start & args]]
  (second ((runner peg) text (or start 0) args)))

(defn- find* [run text start args]
  (let [len (count text)]
    (loop [i start]
      (when (< i len)
        (if-let [r (run text i args)]
          [i r]
          (recur (inc i)))))))

(defn find
  "Index of the first position at or after start where peg matches, or nil."
  [peg text & [start & args]]
  (first (find* (runner peg) text (or start 0) args)))

(defn find-all
  "Vector of every position where peg matches (scanning one char at a time)."
  [peg text & [start & args]]
  (let [run (runner peg) len (count text)]
    (loop [i (or start 0) acc []]
      (if (< i len)
        (recur (inc i) (if (run text i args) (conj acc i) acc))
        acc))))

(defn- subst-text [subst text i e caps]
  (if (fn? subst) (str (apply subst (subs text i e) caps)) (str subst)))

(defn replace
  "Replace the first match of peg in text with subst: a string, or a fn
   called with the matched text followed by the captures."
  [peg subst text & [start & args]]
  (let [run (runner peg)]
    (if-let [[i [e caps]] (find* run text (or start 0) args)]
      (str (subs text 0 i) (subst-text subst text i e caps) (subs text e))
      text)))

(defn replace-all
  "Replace every match of peg in text with subst (string or fn, as `replace`).
   An empty match inserts subst and copies the next char."
  [peg subst text & [start & args]]
  (let [run (runner peg) len (count text) start (or start 0)]
    (loop [i start acc (subs text 0 start)]
      (if (< i len)
        (if-let [[e caps] (run text i args)]
          (let [acc (str acc (subst-text subst text i e caps))]
            (if (= e i)
              (recur (inc i) (str acc (subs text i (inc i))))
              (recur e acc)))
          (recur (inc i) (str acc (subs text i (inc i)))))
        acc))))
