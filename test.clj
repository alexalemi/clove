;; test.clj — clojure.test-compatible battery for clove.
;; (load-file "test.clj") then: deftest / is / testing / run-tests
;;
;; (deftest my-test
;;   (is (= 4 (+ 2 2)))
;;   (is (thrown? Exception (/ 1 0)) "divide by zero throws"))
;; (run-tests)

(def clove/tests (atom []))                ; [[name fn] ...]
(def clove/test-pass (atom 0))
(def clove/test-fail (atom 0))
(def clove/test-context (atom []))
(def clove/test-name (atom nil))           ; deftest name while run-tests runs it

(defmacro deftest [tname & body]
  `(swap! clove/tests conj ['~tname (fn [] ~@body)]))

(defmacro testing [desc & body]
  `(do (swap! clove/test-context conj ~desc)
       (let [r# (do ~@body)]
         (swap! clove/test-context pop)
         r#)))

;; ── structural diff ─────────────────────────────────────────────────────────
;; (clove/diff-paths expected actual) → [{:path [...] :expected x :actual y}
;;                                       {:path [...] :only-in-expected x}
;;                                       {:path [...] :only-in-actual y} ...]
;; Maps walk by key, sequentials by index, sets by membership (the entry's
;; path is the set's own path); anything else, or a type mismatch, is a leaf.
;; Entry count is capped INSIDE the walk: (= (range 3) (range)) is a legal
;; false, and an uncapped index walk over the infinite side never ends. A
;; capped result carries ^{:truncated true}. Seqs walk by first/rest, never
;; count/nth, for the same reason -- and are tested BEFORE the (= e a)
;; shortcut, because clove's = of a finite against an infinite seq hangs.
(defn clove/diff-walk [acc limit path e a]
  (cond
    (>= (count acc) limit) acc
    (identical? e a) acc
    (and (sequential? e) (sequential? a))
    (loop [acc acc i 0 se (seq e) sa (seq a)]
      (cond
        (>= (count acc) limit) acc
        (and (nil? se) (nil? sa)) acc
        (nil? sa) (recur (conj acc {:path (conj path i) :only-in-expected (first se)})
                         (inc i) (next se) nil)
        (nil? se) (recur (conj acc {:path (conj path i) :only-in-actual (first sa)})
                         (inc i) nil (next sa))
        :else (recur (clove/diff-walk acc limit (conj path i) (first se) (first sa))
                     (inc i) (next se) (next sa))))
    (= e a) acc
    (and (map? e) (map? a))
    (reduce (fn [acc k]
              (cond
                (>= (count acc) limit) (reduced acc)
                (not (contains? a k)) (conj acc {:path (conj path k) :only-in-expected (get e k)})
                (not (contains? e k)) (conj acc {:path (conj path k) :only-in-actual (get a k)})
                :else (clove/diff-walk acc limit (conj path k) (get e k) (get a k))))
            acc (distinct (concat (keys e) (keys a))))
    (and (set? e) (set? a))
    (let [acc (reduce (fn [acc x] (if (>= (count acc) limit) (reduced acc)
                                      (conj acc {:path path :only-in-expected x})))
                      acc (remove #(contains? a %) e))]
      (reduce (fn [acc x] (if (>= (count acc) limit) (reduced acc)
                              (conj acc {:path path :only-in-actual x})))
              acc (remove #(contains? e %) a)))
    :else (conj acc {:path path :expected e :actual a})))

(defn clove/diff-paths
  ([expected actual] (clove/diff-paths expected actual 100))
  ([expected actual limit]
   (let [d (clove/diff-walk [] limit [] expected actual)]
     (if (>= (count d) limit) (with-meta d {:truncated true}) d))))

;; Every value goes through this: a failing (= big (range)) must not flood
;; (or hang) the terminal.
(defn clove/test-pr [x]
  (binding [*print-length* 20 *print-level* 6] (pr-str x)))

;; Diff entries → display lines, path column padded to the widest shown path.
;; At most max-lines entries, then "… and N more" (N+ when the walk was capped).
(defn clove/format-diff
  ([entries] (clove/format-diff entries 10))
  ([entries max-lines]
   (let [shown (take max-lines entries)
         paths (map #(clove/test-pr (:path %)) shown)
         w (apply max 0 (map count paths))
         pad (fn [s] (str s (apply str (repeat (- w (count s)) " "))))
         line (fn [p e]
                (str (pad p) "  "
                     (cond
                       (contains? e :only-in-expected) (str "only in expected: " (clove/test-pr (:only-in-expected e)))
                       (contains? e :only-in-actual) (str "only in actual: " (clove/test-pr (:only-in-actual e)))
                       :else (str "expected " (clove/test-pr (:expected e))
                                  ", got " (clove/test-pr (:actual e))))))
         more (- (count entries) (count shown))]
     (cond-> (mapv line paths shown)
       (pos? more) (conj (str "… and " more (when (:truncated (meta entries)) "+") " more"))))))

;; The FAIL header: "FAIL in (my-test) outer > inner  msg". The deftest name
;; comes from run-tests; a bare (is ...) outside it has none.
(defn clove/test-fail-header [msg]
  (let [ctx (str/join " > " @clove/test-context)]
    (str (str/join " " (remove str/blank? ["FAIL" (when-let [n @clove/test-name] (str "in (" n ")")) ctx]))
         (when msg (str "  " msg)))))

(defn clove/test-report
  ([ok form msg] (clove/test-report ok form msg nil))
  ([ok form msg vals]
   (if ok
     (swap! clove/test-pass inc)
     (do (swap! clove/test-fail inc)
         (println (clove/test-fail-header msg))
         (if (and vals (= '= (first form)))
           ;; (= a b ...): show a and the first argument unequal to it
           (let [e (first vals)
                 a (or (first (remove #(= e %) (rest vals))) (second vals))
                 d (clove/diff-paths e a)]
             (println "  expected:" (clove/test-pr e))
             (println "    actual:" (clove/test-pr a))
             (when-not (and (= 1 (count d)) (= [] (:path (first d))))
               (let [ls (clove/format-diff d 10)]
                 (println "      diff:" (first ls))
                 (doseq [l (rest ls)] (println "           " l)))))
           (if vals
             ;; (not= a b ...) failed: every argument was equal
             (do (println "  expected:" (clove/test-pr form))
                 (println "    actual:" (clove/test-pr (list 'not (cons 'not= vals)))))
             (println "  expected:" (clove/test-pr form))))))))

(defmacro is
  ([form] `(is ~form nil))
  ([form msg]
   (cond
     (and (seq? form) (= 'thrown? (first form)))
     `(clove/test-report
        (try (do ~@(drop 2 form)) false
             (catch ~(second form) e# true)
             (catch Throwable e#
               (println "ERROR in is: expected" '~(second form) "but got" (class e#) (ex-message e#))
               false))
        '~form ~msg)
     ;; (= ...)/(not= ...): evaluate each argument once, keep the values for
     ;; the report. The let sits inside the try so a throwing argument still
     ;; takes the ERROR path.
     (and (seq? form) (contains? #{'= 'not=} (first form)) (next form))
     (let [gs (mapv (fn [_] (gensym "v")) (rest form))]
       `(try (let [~@(interleave gs (rest form))]
               (clove/test-report (~(first form) ~@gs) '~form ~msg ~gs))
             (catch Throwable e#
               (println "ERROR in is:" (ex-message e#))
               (clove/test-report false '~form ~msg))))
     :else
     `(clove/test-report
        (try ~form (catch Throwable e#
                     (println "ERROR in is:" (ex-message e#)) false))
        '~form ~msg))))

;; (are [x y] (= x y)  1 1  2 (inc 1)) — template substitution per group
(defn clove/test-subst [smap form]
  (cond
    (contains? smap form) (get smap form)
    (list? form) (apply list (map (fn [f] (clove/test-subst smap f)) form))
    (vector? form) (mapv (fn [f] (clove/test-subst smap f)) form)
    (map? form) (into {} (map (fn [kv] [(clove/test-subst smap (first kv))
                                        (clove/test-subst smap (second kv))])
                              (seq form)))
    :else form))

(defmacro are [argv expr & args]
  (cons 'do
        (map (fn [group] (list 'is (clove/test-subst (zipmap argv group) expr)))
             (partition (count argv) args))))

(defn use-fixtures [& _] nil)   ; fixtures are a no-op (flat env, no vars)

(defn run-tests
  ([& _]
   (reset! clove/test-pass 0)
   (reset! clove/test-fail 0)
   (doseq [[tname f] @clove/tests]
     (reset! clove/test-name tname)
     (try (f) (catch Throwable e
                (swap! clove/test-fail inc)
                (println "ERROR" tname ":" (ex-message e))))
     (reset! clove/test-name nil))
   (println "\nRan" (count @clove/tests) "tests:"
            @clove/test-pass "passed," @clove/test-fail "failed.")
   (zero? @clove/test-fail)))

(def run-all-tests run-tests)

;; ── discovery ───────────────────────────────────────────────────────────────
;; `clove test` with no file arguments walks the tree from . for *_test.clj(c).
;; clove/list-dir* is a native primitive, unlike fs.clj's opendir/readdir FFI
;; binding, so discovery works on Windows and on builds without FFI. It
;; returns nil for a non-directory and [] for an empty one -- and [] is
;; truthy -- so it doubles as the directory test.
(def clove/test-skip-dirs #{"vendor" "target" "node_modules" "out"})

(defn clove/test-file? [n]
  (or (str/ends-with? n "_test.clj") (str/ends-with? n "_test.cljc")))

(defn clove/find-test-files
  ([] (clove/find-test-files "."))
  ([dir]
   (vec (mapcat
          (fn [n]
            (let [p (if (= dir ".") n (str dir "/" n))]
              (cond
                (str/starts-with? n ".")          []      ; .git, .hidden
                (contains? clove/test-skip-dirs n) []
                (clove/list-dir* p)                (clove/find-test-files p)
                (clove/test-file? n)               [p]
                :else                             [])))
          (sort (or (clove/list-dir* dir) []))))))
