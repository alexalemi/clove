;; jit.clj — compile numeric clove functions to native C, live.
;;
;;   (load-file "jit.clj")
;;   (jit/defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
;;   (jit/compile! 'fib)     ; generates C, compiles, swaps the binding
;;   (fib 35)                ; now runs as native machine code
;;
;; Supported subset (unboxed long long / double, and booleans):
;;   literals, params/locals, if, let, loop/recur, self-recursion,
;;   + - * / quot rem mod inc dec < > <= >= = not= zero? pos? neg? not
;;   min max, Math/sqrt, (double x), (long x)
;; Types default to long long; JVM-style ^long/^double hints on the fn name
;; (return) and params pick doubles, and types flow through arithmetic like
;; Clojure's (a double operand makes the step double). Long arithmetic
;; throws on overflow and quot/rem/mod on a zero divisor, as interpreted.
;; quot/rem/mod stay integer-only; / needs at least one double operand (its
;; result is always double — use quot for integer division). Anything the
;; compiled code could not answer the way the interpreter does (an `if`
;; whose branches return a long and a double, `if` with no else, which is
;; nil) fails at compile time with a clear message; the interpreted version
;; stays in place. Args are checked (ints for ^long, numbers for ^double).

(def clove/jit-sources (atom {}))
(def clove/jit-lines (atom []))
(def clove/jit-tmp (atom 0))

(defmacro jit/defn [name params & body]
  `(do (swap! clove/jit-sources assoc '~name '(~name ~params ~@body))
       (defn ~name ~params ~@body)))

(defn clove/jit-fail [msg] (throw (ex-info (str "jit: " msg) {})))
(defn clove/jit-emit [line] (swap! clove/jit-lines conj line))
(defn clove/jit-fresh [prefix] (str prefix (swap! clove/jit-tmp inc)))
(defn clove/jit-tmpvar [] (clove/jit-fresh "t"))
(defn clove/cname [sym] (re-replace (str sym) "[^A-Za-z0-9_]" "_"))
;; Every local gets its own C name: a let that shadows a name must not
;; read the variable it is declaring, and a-b / a_b must not collide.
(defn clove/jit-local [sym] (clove/jit-fresh (str "v_" (clove/cname sym) "_")))

;; ── types ──────────────────────────────────────────────────────────────────
;; :long, :double, :bool, and :recur for a form that never yields a value.
;; tenv maps a local symbol to {:t type :c c-name}.

(defn clove/jit-ctype [t] (case t :double "double" :bool "int" "long long"))

(defn clove/jit-hint [sym]              ; ^long/^double reader hint → type or nil
  (let [tag (:tag (meta sym))]
    (cond (nil? tag) nil
          (= tag 'double) :double
          (= tag 'long) :long
          :else (clove/jit-fail (str "unsupported type hint ^" tag
                                     " on " sym " (only ^long/^double)")))))

(defn clove/jit-num [t form]            ; a type that has to be a number
  (case t
    :bool (clove/jit-fail (str "a boolean used as a number in " (pr-str form)))
    :recur (clove/jit-fail (str "recur not in tail position in " (pr-str form)))
    t))

(defn clove/jit-unify [a b] (if (or (= a :double) (= b :double)) :double :long))

(def clove/jit-cmps #{'< '> '<= '>=})
(def clove/jit-eqs #{'= 'not=})

;; Bindings of a let or loop, in order: [tenv-with-them [[sym type init] ...]].
;; Each init sees the names bound before it, as in Clojure.
(defn clove/jit-bindings [bv tenv ctx form]
  (when-not (and (vector? bv) (even? (count bv)))
    (clove/jit-fail (str "needs a binding vector: " (pr-str form))))
  (loop [i 0 te tenv out []]
    (if (< i (count bv))
      (let [nm (nth bv i)
            init (nth bv (inc i))]
        (when-not (symbol? nm) (clove/jit-fail "let/loop need simple symbols"))
        (let [it (clove/jit-type init te ctx)
              ht (clove/jit-hint nm)]
          (when (= it :recur) (clove/jit-fail (str "recur not in tail position in " (pr-str form))))
          (when (and ht (not= ht it))
            (clove/jit-fail (str nm " is hinted ^" (name ht) " but its value is "
                                 (name it) ": " (pr-str init))))
          (recur (+ i 2) (assoc te nm {:t it :c (clove/jit-local nm)})
                 (conj out [nm it init]))))
      [te out])))

;; Type of `form` under tenv. ctx: {:fn {:name :params :ptypes :ret :pcs}
;; :loop [[c-name type] ...] or nil} — the enclosing function and the
;; innermost loop recur targets.
(defn clove/jit-type [form tenv ctx]
  (cond
    (int? form) :long
    (double? form) :double
    (boolean? form) :bool
    (symbol? form)
    (or (:t (get tenv form))
        (clove/jit-fail (str "free variable " form)))
    (list? form)
    (let [op (first form) args (rest form)
          ts (fn [] (map (fn [a] (clove/jit-type a tenv ctx)) args))
          nums (fn [] (map (fn [t] (clove/jit-num t form)) (ts)))]
      (cond
        (contains? #{'+ '- '*} op)
        (do (when (and (= op '-) (empty? args)) (clove/jit-fail "(-) needs arguments"))
            (reduce clove/jit-unify :long (nums)))
        (contains? #{'min 'max} op)
        (let [t (distinct (nums))]
          (when (empty? args) (clove/jit-fail (str "(" op ") needs arguments")))
          (when (> (count t) 1)
            (clove/jit-fail (str "(" op " ...) mixes long and double; the result's type"
                                 " would depend on the values: " (pr-str form))))
          (first t))
        (contains? #{'quot 'rem 'mod} op)
        (do (when-not (= 2 (count args)) (clove/jit-fail (str op " takes two arguments")))
            (when (some (fn [t] (= t :double)) (nums))
              (clove/jit-fail (str op " is integer-only (got a double operand)")))
            :long)
        (contains? clove/jit-cmps op)
        (do (when (empty? args) (clove/jit-fail (str "(" op ") needs arguments")))
            (dorun (nums)) :bool)
        (contains? clove/jit-eqs op)
        (do (when (empty? args) (clove/jit-fail (str "(" op ") needs arguments")))
            (doseq [t (ts)] (when (= t :recur) (clove/jit-num t form)))
            :bool)
        (contains? #{'zero? 'pos? 'neg?} op) (do (dorun (nums)) :bool)
        (= op 'not) (do (doseq [t (ts)] (when (= t :recur) (clove/jit-num t form))) :bool)
        (= op '/)
        (do (when-not (some (fn [t] (= t :double)) (nums))
              (clove/jit-fail "/ needs a double operand (use quot for integers)"))
            :double)
        (contains? #{'Math/sqrt 'double} op) (do (dorun (nums)) :double)
        (= op 'long) (do (dorun (nums)) :long)
        (contains? #{'inc 'dec} op) (clove/jit-num (clove/jit-type (first args) tenv ctx) form)
        (= op 'if)
        (let [[c a b] args]
          (when-not (= 3 (count args))
            (clove/jit-fail (str "if needs a then and an else branch (a missing else is nil,"
                                 " not a number): " (pr-str form))))
          (let [tc (clove/jit-type c tenv ctx)
                ta (clove/jit-type a tenv ctx)
                tb (clove/jit-type b tenv ctx)]
            (when (= tc :recur) (clove/jit-num tc form))
            (cond (= ta :recur) tb
                  (= tb :recur) ta
                  (= ta tb) ta
                  :else (clove/jit-fail (str "if branches return " (name ta) " and " (name tb)
                                             "; make them the same type: " (pr-str form))))))
        (contains? #{'let 'loop} op)
        (let [[te binds] (clove/jit-bindings (first args) tenv ctx form)
              body (rest args)
              ctx2 (if (= op 'loop)
                     (assoc ctx :loop (mapv (fn [[nm t _]] [(:c (get te nm)) t]) binds))
                     ctx)]
          (when (empty? body) (clove/jit-fail (str op " needs a body")))
          (doseq [b (butlast body)]
            (when (= :recur (clove/jit-type b te ctx2))
              (clove/jit-fail (str "recur not in tail position in " (pr-str form)))))
          (clove/jit-type (last body) te ctx2))
        (= op 'recur)
        (let [targets (or (:loop ctx)
                          (mapv vector (:pcs (:fn ctx)) (:ptypes (:fn ctx))))]
          (when-not (= (count targets) (count args))
            (clove/jit-fail (str "recur expects " (count targets) " arguments, got "
                                 (count args) ": " (pr-str form))))
          (doseq [[[_ t] a] (map vector targets args)]
            (let [at (clove/jit-type a tenv ctx)]
              (when-not (= at t)
                (clove/jit-fail (str "recur passes a " (name at) " where the loop has a "
                                     (name t) ": " (pr-str a)
                                     " (hint the loop name or match the init's type)")))))
          :recur)
        (= op (:name (:fn ctx)))
        (do (when-not (= (count args) (count (:params (:fn ctx))))
              (clove/jit-fail (str op " expects " (count (:params (:fn ctx))) " arguments")))
            (doseq [[a t] (map vector args (:ptypes (:fn ctx)))]
              (let [at (clove/jit-num (clove/jit-type a tenv ctx) form)]
                (when (and (= t :long) (= at :double))
                  (clove/jit-fail (str "passes a double to ^long parameter: " (pr-str form))))))
            (:ret (:fn ctx)))
        :else (clove/jit-fail (str "unsupported form " (pr-str form)))))
    :else (clove/jit-fail (str "unsupported literal " (pr-str form)))))

;; ── emission ───────────────────────────────────────────────────────────────
;; Emit C statements computing `form` into C variable `dst`, declared by the
;; caller with exactly the type clove/jit-type reports for `form`.

(defn clove/jit-tmp-for [form tenv ctx]  ; declare + fill a temp for form
  (let [t (clove/jit-tmpvar)]
    (clove/jit-emit (str (clove/jit-ctype (clove/jit-type form tenv ctx)) " " t ";"))
    (clove/jit-expr form t tenv ctx)
    t))

(defn clove/jit-double-lit [d]
  (cond (not= d d) "(0.0 / 0.0)"
        (= d ##Inf) "(1.0 / 0.0)"
        (= d ##-Inf) "(-1.0 / 0.0)"
        :else (pr-str d)))

(defn clove/jit-long-lit [n]
  (if (= n Long/MIN_VALUE) "(-9223372036854775807LL - 1)" (str n "LL")))

;; Left fold of + - * over typed temps, as Clojure evaluates (+ a b c):
;; long steps are overflow-checked, a double operand makes the step double.
(defn clove/jit-arith [op tts dst]
  (let [builtin (case op + "add" - "sub" * "mul")
        cop (str op)]
    (loop [[acc at] (first tts) more (rest tts)]
      (if (empty? more)
        (clove/jit-emit (str dst " = " acc ";"))
        (let [[b bt] (first more)
              t (clove/jit-unify at bt)
              r (clove/jit-tmpvar)]
          (clove/jit-emit (str (clove/jit-ctype t) " " r ";"))
          (if (= t :long)
            (clove/jit-emit (str "if (__builtin_" builtin "_overflow(" acc ", " b ", &" r ")) jit_ovf();"))
            (clove/jit-emit (str r " = (double)" acc " " cop " (double)" b ";")))
          (recur [r t] (rest more)))))))

(defn clove/jit-expr [form dst tenv ctx]
  (cond
    (int? form) (clove/jit-emit (str dst " = " (clove/jit-long-lit form) ";"))
    (double? form) (clove/jit-emit (str dst " = " (clove/jit-double-lit form) ";"))
    (boolean? form) (clove/jit-emit (str dst " = " (if form 1 0) ";"))
    (symbol? form) (clove/jit-emit (str dst " = " (:c (get tenv form)) ";"))
    (list? form)
    (let [op (first form) args (rest form)
          typed (fn [] (mapv (fn [a] [(clove/jit-tmp-for a tenv ctx) (clove/jit-type a tenv ctx)]) args))]
      (cond
        (contains? #{'+ '- '*} op)
        (let [tts (typed)]
          (cond
            (empty? tts) (clove/jit-emit (str dst " = " (if (= op '*) 1 0) "LL;"))
            (and (= op '-) (= 1 (count tts)))
            (let [[a t] (first tts)]
              (if (= t :long)
                (clove/jit-emit (str "if (__builtin_sub_overflow(0LL, " a ", &" dst ")) jit_ovf();"))
                (clove/jit-emit (str dst " = -" a ";"))))
            :else (clove/jit-arith op tts dst)))
        (contains? #{'min 'max} op)
        (let [tts (typed)
              dbl (= :double (second (first tts)))
              cmp (if (= op 'min) " < " " > ")]
          (clove/jit-emit (str dst " = " (first (first tts)) ";"))
          (doseq [[b _] (rest tts)]
            (clove/jit-emit
             (if dbl     ; Math/min and Math/max: NaN wins
               (str dst " = (" dst " != " dst " || " b " != " b ") ? (0.0 / 0.0) : ("
                    b cmp dst " ? " b " : " dst ");")
               (str dst " = " b cmp dst " ? " b " : " dst ";")))))
        (contains? #{'quot 'rem 'mod} op)
        (let [[[a _] [b _]] (typed)]
          (clove/jit-emit (str "if (" b " == 0) jit_div0();"))
          (case op
            ;; Long/MIN_VALUE by -1 traps in C; the JVM wraps quot and gives 0 for rem
            quot (clove/jit-emit (str dst " = " b " == -1 ? (long long)(0ULL - (unsigned long long)" a
                                      ") : " a " / " b ";"))
            (rem mod) (clove/jit-emit (str dst " = " b " == -1 ? 0 : " a " % " b ";")))
          (when (= op 'mod)
            (clove/jit-emit (str "if (" dst " != 0 && ((" dst " < 0) != (" b " < 0))) "
                                 dst " += " b ";"))))
        (contains? clove/jit-cmps op)
        (let [ts (mapv first (typed))]
          (clove/jit-emit
           (str dst " = " (if (= 1 (count ts)) "1"
                              (str/join " && " (map (fn [a b] (str "(" a " " op " " b ")"))
                                                    (butlast ts) (rest ts))))
                ";")))
        (contains? clove/jit-eqs op)
        (let [tts (typed)
              ;; = is false across categories: (= 1 1.0) and (= 1 true) are false
              pair (fn [[a at] [b bt]] (if (= at bt) (str "(" a " == " b ")") "0"))
              all (if (= 1 (count tts)) "1"
                      (str/join " && " (map pair (butlast tts) (rest tts))))]
          (clove/jit-emit (str dst " = " (if (= op 'not=) (str "!(" all ")") all) ";")))
        (= op 'zero?) (clove/jit-emit (str dst " = (" (clove/jit-tmp-for (first args) tenv ctx) " == 0);"))
        (= op 'pos?) (clove/jit-emit (str dst " = (" (clove/jit-tmp-for (first args) tenv ctx) " > 0);"))
        (= op 'neg?) (clove/jit-emit (str dst " = (" (clove/jit-tmp-for (first args) tenv ctx) " < 0);"))
        (= op 'not)
        (let [[[a t]] (typed)]          ; numbers are truthy, so (not n) is false
          (clove/jit-emit (str dst " = " (if (= t :bool) (str "!" a) "0") ";")))
        (= op '/)
        (let [ts (mapv first (typed))]
          (if (= 1 (count ts))
            (clove/jit-emit (str dst " = 1.0 / " (first ts) ";"))
            (clove/jit-emit (str dst " = (double)" (str/join " / (double)" (seq ts)) ";"))))
        (= op 'Math/sqrt)
        (clove/jit-emit (str dst " = sqrt((double)" (clove/jit-tmp-for (first args) tenv ctx) ");"))
        (= op 'double)
        (clove/jit-emit (str dst " = (double)" (clove/jit-tmp-for (first args) tenv ctx) ";"))
        (= op 'long)
        (let [[[a t]] (typed)]
          (if (= t :double)
            ;; RT.longCast: NaN is 0, out of range throws, else truncate
            (clove/jit-emit (str "if (" a " != " a ") " dst " = 0; "
                                 "else if (!(" a " >= -9223372036854775808.0 && " a
                                 " < 9223372036854775808.0)) jit_range(); "
                                 "else " dst " = (long long)" a ";"))
            (clove/jit-emit (str dst " = " a ";"))))
        (contains? #{'inc 'dec} op)
        (let [[[a t]] (typed)]
          (if (= t :long)
            (clove/jit-emit (str "if (__builtin_" (if (= op 'inc) "add" "sub") "_overflow("
                                 a ", 1LL, &" dst ")) jit_ovf();"))
            (clove/jit-emit (str dst " = " a (if (= op 'inc) " + 1" " - 1") ";"))))
        (= op 'if)
        (let [[c a b] args
              tc (clove/jit-tmp-for c tenv ctx)]
          (if (= :bool (clove/jit-type c tenv ctx))
            (do (clove/jit-emit (str "if (" tc ") {"))
                (clove/jit-expr a dst tenv ctx)
                (clove/jit-emit "} else {")
                (clove/jit-expr b dst tenv ctx)
                (clove/jit-emit "}"))
            (clove/jit-expr a dst tenv ctx)))   ; a number is always truthy, 0 included
        (contains? #{'let 'loop} op)
        (let [[te binds] (clove/jit-bindings (first args) tenv ctx form)
              body (rest args)]
          ;; declare and fill in order, each init under the names before it
          (loop [bs binds te-so-far tenv]
            (when (seq bs)
              (let [[nm t init] (first bs)
                    c (:c (get te nm))]
                (clove/jit-emit (str (clove/jit-ctype t) " " c ";"))
                (clove/jit-expr init c te-so-far ctx)
                (recur (rest bs) (assoc te-so-far nm (get te nm))))))
          (let [ctx2 (if (= op 'loop)
                       (assoc ctx :loop (mapv (fn [[nm t _]] [(:c (get te nm)) t]) binds))
                       ctx)]
            (when (= op 'loop) (clove/jit-emit "for (;;) {"))
            (doseq [b (butlast body)] (clove/jit-tmp-for b te ctx2))
            (clove/jit-expr (last body) dst te ctx2)
            (when (= op 'loop) (clove/jit-emit "break; }"))))
        (= op 'recur)
        (let [targets (or (:loop ctx) (mapv vector (:pcs (:fn ctx)) (:ptypes (:fn ctx))))
              tmps (mapv (fn [a] (clove/jit-tmp-for a tenv ctx)) args)]
          (doseq [[[c _] t] (map vector targets tmps)]
            (clove/jit-emit (str c " = " t ";")))
          (clove/jit-emit "continue;"))
        (= op (:name (:fn ctx)))
        (let [ts (mapv first (typed))]
          (clove/jit-emit (str dst " = impl_" (clove/cname op) "(" (str/join ", " (seq ts)) ");")))
        :else (clove/jit-fail (str "unsupported form " (pr-str form)))))
    :else (clove/jit-fail (str "unsupported literal " (pr-str form)))))

;; Return type: the ^long/^double hint on the name, else the body's type. A
;; self-call's type is the return type itself, so try each guess and keep
;; the one the body agrees with.
(defn clove/jit-ret-type [fname name-sym body fnctx tenv]
  (let [check (fn [guess]
                (let [ctx {:fn (assoc fnctx :ret guess) :loop nil}]
                  (doseq [b (butlast body)]
                    (when (= :recur (clove/jit-type b tenv ctx))
                      (clove/jit-fail "recur not in tail position")))
                  (clove/jit-type (last body) tenv ctx)))
        hint (clove/jit-hint name-sym)]
    (if hint
      (let [t (check hint)]
        (when-not (contains? #{hint :recur} t)
          (clove/jit-fail (str fname " is hinted ^" (name hint) " but returns " (name t))))
        hint)
      (loop [gs [:long :double :bool] first-err nil]
        (if (empty? gs)
          (throw (or first-err (ex-info (str "jit: can't infer the return type of " fname) {})))
          (let [g (first gs)
                [t e] (try [(check g) nil] (catch Exception e [nil e]))]
            (if (= t g) g (recur (rest gs) (or first-err e)))))))))

(defn jit/compile! [fname]
  (let [src (get @clove/jit-sources fname)]
    (when-not src (clove/jit-fail (str "no source for " fname " (define with jit/defn)")))
    (reset! clove/jit-lines [])
    (reset! clove/jit-tmp 0)
    (let [name-sym (first src)
          params (second src)
          body (rest (rest src))
          cn (clove/cname fname)
          _ (when-not (and (vector? params) (every? symbol? params) (not-any? #{'&} params))
              (clove/jit-fail "params must be plain symbols (no destructuring or &)"))
          _ (when (empty? body) (clove/jit-fail (str fname " has no body")))
          ptypes (mapv (fn [p] (or (clove/jit-hint p) :long)) params)
          pcs (mapv (fn [p] (clove/jit-local p)) params)
          tenv (into {} (map (fn [p t c] [p {:t t :c c}]) params ptypes pcs))
          fnctx {:name fname :params params :ptypes ptypes :pcs pcs}
          ret (clove/jit-ret-type fname name-sym body fnctx tenv)
          ctx {:fn (assoc fnctx :ret ret) :loop nil}]
      (clove/jit-emit (str "static " (clove/jit-ctype ret) " impl_" cn "("
                          (str/join ", " (map (fn [c t] (str (clove/jit-ctype t) " " c)) pcs ptypes))
                          ") {"))
      (clove/jit-emit (str (clove/jit-ctype ret) " result;"))
      ;; fn-level recur: params are the loop vars
      (clove/jit-emit "for (;;) {")
      (doseq [b (butlast body)] (clove/jit-tmp-for b tenv ctx))
      (clove/jit-expr (last body) "result" tenv ctx)
      (clove/jit-emit "break; }")
      (clove/jit-emit "return result; }")
      (let [mk (case ret :double "mk_double" :bool "mk_bool" "mk_int")
            code (str "#include <math.h>\n"
                      clove/ffi-api-decl
                      "static void jit_ovf(void) { api->error_cls(\"ArithmeticException\", \"long overflow\"); }\n"
                      "static void jit_div0(void) { api->error_cls(\"ArithmeticException\", \"Divide by zero\"); }\n"
                      "static void jit_range(void) { api->error_cls(\"IllegalArgumentException\", \"Value out of range for long\"); }\n"
                      (str/join "\n" @clove/jit-lines) "\n"
                      "static void *w_" cn "(void *env, void *args, int nargs) {\n"
                      "  (void)env;\n"
                      "  if (nargs != " (count params) ") api->error(\"" fname ": wrong arity\");\n"
                      "  return api->" mk "(impl_" cn "("
                      (str/join ", " (map (fn [i t]
                                            (str "api->" (if (= t :double) "as_double" "as_int")
                                                 "(api->nth_arg(args, " i "))"))
                                          (range (count params)) (seq ptypes)))
                      "));\n}\n"
                      "void clove_module_init(void *env, CloveFfiApi *a) { api = a;\n"
                      "  api->def_native(env, \"" fname "\", w_" cn ");\n}\n")]
        (clove/ffi-build code "-lm")
        fname))))
