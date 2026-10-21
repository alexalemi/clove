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

(defmacro deftest [tname & body]
  `(swap! clove/tests conj ['~tname (fn [] ~@body)]))

(defmacro testing [desc & body]
  `(do (swap! clove/test-context conj ~desc)
       (let [r# (do ~@body)]
         (swap! clove/test-context pop)
         r#)))

(defn clove/test-report [ok form msg]
  (if ok
    (swap! clove/test-pass inc)
    (do (swap! clove/test-fail inc)
        (println "FAIL" (str/join " > " @clove/test-context)
                 (or msg "") "\n  expected:" (pr-str form)))))

(defmacro is
  ([form] `(is ~form nil))
  ([form msg]
   (if (and (list? form) (= 'thrown? (first form)))
     `(clove/test-report
        (try (do ~@(drop 2 form)) false
             (catch ~(second form) e# true)
             (catch Throwable e#
               (println "ERROR in is: expected" '~(second form) "but got" (class e#) (ex-message e#))
               false))
        '~form ~msg)
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
     (swap! clove/test-context conj (str tname))
     (try (f) (catch Throwable e
                (swap! clove/test-fail inc)
                (println "ERROR" tname ":" (ex-message e))))
     (swap! clove/test-context pop))
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
