;; clerk.clj — Clerk-style literate notebooks for clove.
;;
;;   clove notebook notebook.clj [port]     live server (default 7878)
;;   clove notebook somedir/ [port]         watch the tree; saving any .clj
;;                                         in it switches the view to it
;;   clove notebook notebook.clj -o out.html  static single-file build
;;   clove notebook notebook.clj -o out.html -S
;;                                         standalone: the page embeds clove
;;                                         compiled to wasm, and cells can be
;;                                         edited and re-run in the browser
;;   clove notebook notebook.clj -o out.html -S --wasm-url URL
;;                                         same, but load the interpreter from
;;                                         URL instead of inlining it (~1.3 MB)
;;   clove notebook --wasm clove-wasm.js   write that interpreter file, to host
;;
;; A notebook is an ordinary .clj file: top-level ;; comments render as
;; markdown prose, every top-level form is a code cell evaluated in order
;; (shared env, errors isolated per cell). Single-; comments are invisible.
;; Directives above a form: ;; @clerk:hide-code  ;; @clerk:hide-result
;;
;; Percent cells (opt-in, as in jupytext): if any line is a `;; %%` marker,
;; a code cell is instead the whole run of code between boundaries, which are
;; `;; %%` lines (any text after the %% is ignored), prose, and directives.
;; Every form in the cell runs; only the LAST form's value is shown, so end a
;; cell with nil to suppress its output. Single-; comments inside
;; such a cell are kept as part of its source.
;;
;; Uses only core natives (tcp/*, clove/mtime*, with-out-str) — no FFI, so
;; it works without a C compiler available.
;;
;; Standalone pages (-S) need emscripten's emcc (override with CLOVE_EMCC):
;; clove.c is compiled once per clove.c version and cached. In the browser the
;; page runs this file's own clerk/render-code, so re-run cells render as they
;; do here; it first re-runs every cell in order so later cells see earlier
;; defs, then each cell re-runs alone against that environment (Jupyter
;; semantics). Inside wasm there are no coroutines (core.async, future,
;; agents), no sh/FFI/sockets/local files, and recursion stops at roughly
;; 1000 frames (a catchable StackOverflowError). A deeper engine-level
;; overflow kills the interpreter and the page asks for a reload.

;; ── cell parser ──
;; Line scanner: track paren depth and in-string across lines; prose and
;; directives are only recognized at depth 0 between forms.

(defn clove/clerk-scan-line
  "Advance [depth in-string?] across one line of code."
  [line depth instr]
  (loop [i 0 d depth instr instr]
    (if (>= i (count line))
      [d instr]
      (let [c (subs line i (inc i))]
        (cond
          instr (cond (= c "\\") (recur (+ i 2) d true)
                      (= c "\"") (recur (inc i) d false)
                      :else      (recur (inc i) d true))
          (= c ";")  [d false]                       ; comment to EOL
          (= c "\"") (recur (inc i) d true)
          (str/includes? "([{" c) (recur (inc i) (inc d) false)
          (str/includes? ")]}" c) (recur (inc i) (dec d) false)
          :else (recur (inc i) d false))))))

(defn clerk/percent-marker? [t] (boolean (re-matches #";; ?%%.*" t)))

(defn clerk/parse
  "Notebook source → cells [{:kind :md :text s} {:kind :code :text s :dirs m}].
  Modes: nil (between cells), :md, :code (inside an unfinished form), and
  :gap (percent mode only: a code cell is open, between complete forms)."
  [src]
  (let [lines (str/split src "\n")
        pct? (boolean (some (fn [l] (clerk/percent-marker? (str/trim l))) lines))
        drop-blanks (fn [a] (loop [a a]
                              (if (and (seq a) (= (str/trim (peek a)) "")) (recur (pop a)) a)))
        flush-cell (fn [cells mode acc dirs]
                     (let [acc (drop-blanks acc)]
                       (if (empty? acc)
                         cells
                         (conj cells (if (= mode :md)
                                       {:kind :md :text (str/join "\n" acc)}
                                       {:kind :code :text (str/join "\n" acc)
                                        :dirs dirs})))))
        ;; directives are pending for the NEXT code cell; once a code cell is
        ;; flushed, its directives are spent
        dirs-after (fn [mode dirs] (if (= mode :gap) {} dirs))]
    (loop [lines lines
           cells [] mode nil acc [] depth 0 instr false dirs {}]
      (if (empty? lines)
        (flush-cell cells mode acc dirs)
        (let [line (first lines)
              more (rest lines)
              t (str/trim line)]
          (if (= mode :code)
            ;; inside a form: accumulate until depth returns to 0
            (let [[d i2] (clove/clerk-scan-line line depth instr)
                  acc (conj acc line)]
              (cond
                (or (> d 0) i2) (recur more cells :code acc d i2 dirs)
                pct?            (recur more cells :gap acc 0 false dirs)
                :else (recur more (flush-cell cells :code acc dirs) nil [] 0 false {})))
            ;; at top level
            (cond
              ;; percent marker: ;; %% [title] — always a cell boundary
              (and pct? (clerk/percent-marker? t))
              (recur more (flush-cell cells mode acc dirs) nil [] 0 false
                     (dirs-after mode dirs))
              ;; directive: ;; @clerk:name [arg] — applies to the next code cell
              (re-matches #";; ?@clerk:.*" t)
              (let [[_ nm arg] (re-matches #";; ?@clerk:(\S+) ?(.*)" t)]
                (recur more (flush-cell cells mode acc dirs) nil []
                       0 false (assoc (dirs-after mode dirs) nm (if (= arg "") true arg))))
              ;; prose: top-level ;; comment
              (str/starts-with? t ";;")
              (let [text (subs t 2)
                    text (if (str/starts-with? text " ") (subs text 1) text)]
                (if (= mode :md)
                  (recur more cells :md (conj acc text) 0 false dirs)
                  (recur more (flush-cell cells mode acc dirs) :md [text]
                         0 false (dirs-after mode dirs))))
              ;; blank: paragraph break in prose, kept inside a percent cell,
              ;; separator otherwise
              (= t "")
              (if (contains? #{:md :gap} mode)
                (recur more cells mode (conj acc "") 0 false dirs)
                (recur more (flush-cell cells mode acc dirs) nil [] 0 false dirs))
              ;; single-; comment: part of an open percent cell's source,
              ;; otherwise invisible (not rendered, not code)
              (str/starts-with? t ";")
              (if (= mode :gap)
                (recur more cells :gap (conj acc line) 0 false dirs)
                (recur more cells mode acc 0 false dirs))
              ;; code line
              :else
              (let [[cells acc] (if (= mode :gap)
                                  [cells (conj acc line)]
                                  [(flush-cell cells mode acc dirs) [line]])
                    [d i2] (clove/clerk-scan-line line 0 false)]
                (cond
                  (or (> d 0) i2) (recur more cells :code acc d i2 dirs)
                  pct?            (recur more cells :gap acc 0 false dirs)
                  :else (recur more (flush-cell cells :code acc dirs) nil [] 0 false {}))))))))))

;; ── evaluation ──

(defn clerk/eval-cell
  "Eval one code cell in the root env. Captures stdout; isolates errors."
  [text]
  (let [val (atom nil)
        err (atom nil)
        out (with-out-str
              ;; a def cell shows (and caches) the value, as Clerk does
              (try (reset! val (let [v (eval (read-string (str "(do " text "\n)")))]
                                 (if (var? v) @v v)))
                   (catch Throwable e (reset! err (ex-message e)))))]
    {:value @val :error @err :out out}))

;; ── incremental cache ──
;; Live editing re-renders the whole file on every save; naively that
;; re-evaluates every cell each time. We cache each code cell's eval RESULT
;; under a content key that folds in the cell text PLUS the keys of the earlier
;; cells it depends on — a cell depends on an earlier one when it refers to a
;; symbol that one defines (the def name is in a cell's refs too, so a redef
;; depends on the earlier def). So editing a cell re-runs it and everything
;; downstream, while untouched cells are reused.
;;
;; To keep the shared mutable env correct in CELL ORDER regardless of what
;; changed, a cached simple-def cell REPLAYS its binding each render (rebinds
;; the name to the cached value, no recompute) — so a later redef always wins,
;; and removing a redef falls back to the earlier def. Expression cells have no
;; env effect, so a cache hit just reuses them; macro/type/multi-form defs
;; (which we can't replay by value) recompute. Net: only changed cells and
;; their dependents recompute, but a def's expensive body never re-runs when
;; unchanged. Keyed by text (not value), so a whitespace edit to a dependency
;; still invalidates dependents: safe, just less optimal than nippy Clerk.

(def clerk/eval-cache (atom {}))

(defn clerk/syms-in
  "Every symbol appearing anywhere in a read form (an over-approx of refs)."
  [form]
  (cond
    (symbol? form) #{form}
    (or (list? form) (vector? form) (set? form))
      (reduce (fn [a f] (into a (clerk/syms-in f))) #{} (seq form))
    (map? form)
      (reduce (fn [a kv] (into (into a (clerk/syms-in (first kv)))
                               (clerk/syms-in (second kv)))) #{} (seq form))
    :else #{}))

(def clerk/def-heads
  #{'def 'defn 'defn- 'defmacro 'defonce 'defmulti 'defrecord 'deftype})

(defn clerk/form-defs
  "Top-level names a form defines (for dependency tracking), or nil."
  [form]
  (when (and (list? form) (symbol? (first form)))
    (cond
      (contains? clerk/def-heads (first form))
        (when (symbol? (second form)) #{(second form)})
      (= 'declare (first form)) (set (filter symbol? (rest form)))
      :else nil)))

(defn clerk/cell-analyze
  "{:defs #{names} :refs #{symbols}} for a cell's source (read errors -> empty)."
  [text]
  (let [forms (try (read-string (str "[" text "\n]")) (catch Exception e []))]
    {:defs (reduce (fn [a f] (into a (or (clerk/form-defs f) #{}))) #{} forms)
     :refs (reduce (fn [a f] (into a (clerk/syms-in f))) #{} forms)}))

(defn clerk/cell-key [text dep-keys]
  (hash [text (vec (sort dep-keys))]))

(defn clerk/simple-def-name
  "If a cell is exactly one simple def (def/defn/defn-/defonce name ...), the
  name — whose value we can re-assert from cache without recomputing the body.
  Expression cells and macro/type/multi-form defs return nil."
  [text]
  (let [forms (try (read-string (str "[" text "\n]")) (catch Exception e []))]
    (when (= 1 (count forms))
      (let [f (first forms)]
        (when (and (list? f)
                   (contains? #{'def 'defn 'defn- 'defonce} (first f))
                   (symbol? (second f)))
          (second f))))))

(defn clerk/replay-def!
  "Re-assert name -> cached value WITHOUT recomputing the body. Quote so any
  value (including a fn) binds literally."
  [name value]
  (eval (list 'def name (list 'quote value))))

;; ── html escaping + markdown subset ──

(defn clerk/escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn clove/clerk-splice
  "Replace the leftmost re-find hit with (render groups), recursing on the
  text either side. Backref-free substitute for regex replace."
  [s re render recur-fn]
  (if-let [m (re-find re s)]
    (let [whole (if (vector? m) (first m) m)
          i (str/index-of s whole)]
      (str (recur-fn (subs s 0 i))
           (render m)
           (recur-fn (subs s (+ i (count whole))))))
    nil))

(defn clove/clerk-inline-fmt
  "Links, bold, italic on already-escaped text (no backtick content)."
  [s]
  (or (clove/clerk-splice s #"\[([^\]]+)\]\(([^)]+)\)"
                         (fn [[_ text url]]
                           (str "<a href=\"" url "\">" text "</a>"))
                         clove/clerk-inline-fmt)
      (clove/clerk-splice s #"\*\*([^*]+)\*\*"
                         (fn [[_ b]] (str "<strong>" b "</strong>"))
                         clove/clerk-inline-fmt)
      (clove/clerk-splice s #"\*([^*]+)\*"
                         (fn [[_ i]] (str "<em>" i "</em>"))
                         clove/clerk-inline-fmt)
      s))

(defn clove/clerk-inline
  "Inline markdown: split on backticks — odd segments are code spans."
  [s]
  (let [parts (str/split s "`")]
    (str/join ""
      (map-indexed (fn [i p]
                     (if (odd? i)
                       (str "<code>" (clerk/escape p) "</code>")
                       (clove/clerk-inline-fmt (clerk/escape p))))
                   parts))))

(declare clerk/hl)

(def clerk/clj-fence-langs #{"" "clj" "clojure" "cljc" "cljs" "edn"})

(defn clerk/md->html
  "Markdown subset: #/##/### headers, - and 1. lists (a line that doesn't
  start a new block continues the open item), ``` fenced code (Clojure
  fences highlighted), > quotes, | tables |, paragraphs, inline
  code/links/bold/italic.
  $...$ passes through untouched for KaTeX."
  [text]
  (let [inl clove/clerk-inline
        cells (fn [row] (mapv str/trim (str/split (str/replace (subs row 1) #"\|$" "") "|")))
        ;; | a | b | rows; the |---| row after the first marks it a header,
        ;; and a header of empty cells is dropped
        table (fn [rows]
                (let [sep? (fn [r] (re-matches #"[|:\s-]+" r))
                      [head body] (if (and (second rows) (sep? (second rows)))
                                    [(cells (first rows)) (drop 2 rows)]
                                    [nil rows])
                      tr (fn [tag cs] (str "<tr>" (apply str (map (fn [c] (str "<" tag ">" (inl c) "</" tag ">")) cs)) "</tr>"))]
                  (str "<table>"
                       (when (some seq head) (str "<thead>" (tr "th" head) "</thead>"))
                       "<tbody>" (apply str (map (fn [r] (tr "td" (cells r))) body)) "</tbody></table>")))
        close-item (fn [st]
                     (if (:item st)
                       (assoc st :item nil
                                 :html (str (:html st) "<li>" (inl (str/join " " (:item st))) "</li>"))
                       st))
        ;; close every open block: paragraph, list, quote
        close (fn [st]
                (let [st (close-item st)
                      h (:html st)
                      h (if (seq (:para st)) (str h "<p>" (inl (str/join " " (:para st))) "</p>") h)
                      h (case (:list st) :ul (str h "</ul>") :ol (str h "</ol>") h)
                      h (if (seq (:quote st))
                          (str h "<blockquote>" (clerk/md->html (str/join "\n" (:quote st))) "</blockquote>")
                          h)
                      h (if (seq (:table st)) (str h (table (:table st))) h)]
                  (assoc st :html h :para [] :list nil :quote [] :table [])))
        start-item (fn [st kind item]
                     (let [st (if (= (:list st) kind)
                                (close-item st)
                                (let [c (close st)]
                                  (assoc c :list kind
                                           :html (str (:html c) (if (= kind :ol) "<ol>" "<ul>")))))]
                       (assoc st :item [item])))
        emit (fn [st tag body] (let [c (close st)] (assoc c :html (str (:html c) "<" tag ">" (inl body) "</" tag ">"))))
        code (fn [{:keys [lang lines]}]
               (let [src (str/join "\n" lines)]
                 (str "<pre class=\"src\">"
                      (if (contains? clerk/clj-fence-langs lang) (clerk/hl src) (clerk/escape src))
                      "</pre>")))]
    (loop [lines (str/split text "\n")
           st {:html "" :para [] :list nil :item nil :quote [] :table [] :fence nil}]
      (if (empty? lines)
        (:html (close (if (:fence st)          ; unterminated fence: keep it
                        (assoc st :html (str (:html st) (code (:fence st))) :fence nil)
                        st)))
        (let [raw (first lines)
              t (str/trim raw)
              more (rest lines)]
          (cond
            (:fence st)
            (if (str/starts-with? t "```")
              (recur more (assoc st :html (str (:html st) (code (:fence st))) :fence nil))
              (recur more (update-in st [:fence :lines] conj raw)))
            (str/starts-with? t "```")
            (recur more (assoc (close st) :fence {:lang (str/trim (subs t 3)) :lines []}))
            (str/starts-with? t "### ") (recur more (emit st "h3" (subs t 4)))
            (str/starts-with? t "## ")  (recur more (emit st "h2" (subs t 3)))
            (str/starts-with? t "# ")   (recur more (emit st "h1" (subs t 2)))
            (str/starts-with? t "- ")   (recur more (start-item st :ul (subs t 2)))
            (re-matches #"\d+\. .*" t)  (recur more (start-item st :ol (second (re-matches #"\d+\. (.*)" t))))
            (or (= t ">") (str/starts-with? t "> "))
            (let [st (if (seq (:quote st)) st (close st))]
              (recur more (update st :quote conj (if (= t ">") "" (subs t 2)))))
            (and (str/starts-with? t "|") (not (:item st)))
            (let [st (if (seq (:table st)) st (close st))]
              (recur more (update st :table conj t)))
            (= t "") (recur more (close st))
            (:item st)           (recur more (update st :item conj t))     ; wrapped list item
            (seq (:quote st))    (recur more (update st :quote conj t))    ; lazy quote line
            :else                (recur more (update st :para conj t))))))))

;; ── code syntax highlighting (server-side, span classes) ──

(defn clove/clerk-scan-while [s i pred]
  (loop [j i]
    (if (and (< j (count s)) (pred (subs s j (inc j)))) (recur (inc j)) j)))

;; Special forms + the def/macro family get their own colour so the most
;; salient tokens (defn, let, if, map-position keywords) actually stand out.
(def clerk/hl-specials
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defrecord"
    "defprotocol" "deftype" "definline" "let" "letfn" "fn" "loop" "recur"
    "if" "if-not" "if-let" "if-some" "when" "when-not" "when-let" "when-some"
    "cond" "condp" "case" "do" "doseq" "dotimes" "for" "while" "try" "catch"
    "finally" "throw" "quote" "var" "ns" "require" "use" "import" "binding"
    "and" "or" "not" "->" "->>" "as->" "some->" "some->>" "cond->" "cond->>"
    "fn*" "let*" "lazy-seq" "delay" "future" "declare" "in-ns"})

(defn clerk/hl
  "Code → HTML with .com .str .kwd .lit .pun .spe spans."
  [code]
  (let [n (count code)
        token-char? (fn [c] (not (or (str/blank? c) (str/includes? "()[]{}\";" c))))]
    (loop [i 0 out ""]
      (if (>= i n)
        out
        (let [c (subs code i (inc i))]
          (cond
            (= c ";")
            (let [j (or (str/index-of code "\n" i) n)]
              (recur j (str out "<span class=\"com\">" (clerk/escape (subs code i j)) "</span>")))
            (= c "\"")
            (let [j (loop [k (inc i)]
                      (cond (>= k n) n
                            (= (subs code k (inc k)) "\\") (recur (+ k 2))
                            (= (subs code k (inc k)) "\"") (inc k)
                            :else (recur (inc k))))]
              (recur j (str out "<span class=\"str\">" (clerk/escape (subs code i j)) "</span>")))
            (= c ":")
            (let [j (clove/clerk-scan-while code (inc i) token-char?)]
              (recur j (str out "<span class=\"kwd\">" (clerk/escape (subs code i j)) "</span>")))
            (str/includes? "0123456789" c)
            (let [j (clove/clerk-scan-while code i token-char?)]
              (recur j (str out "<span class=\"lit\">" (clerk/escape (subs code i j)) "</span>")))
            (str/includes? "()[]{}" c)
            (recur (inc i) (str out "<span class=\"pun\">" (clerk/escape c) "</span>"))
            (token-char? c)
            (let [j (clove/clerk-scan-while code i token-char?)
                  tok (subs code i j)]
              (recur j (str out (if (contains? clerk/hl-specials tok)
                                  (str "<span class=\"spe\">" (clerk/escape tok) "</span>")
                                  (clerk/escape tok)))))
            :else (recur (inc i) (str out (clerk/escape c)))))))))

;; ── viewers ──
;; Last registered wins. (clerk/register-viewer! pred render) from notebook
;; code customizes display; render returns an HTML string.

(def clerk/viewers (atom []))

(defn clerk/register-viewer! [pred render]
  (swap! clerk/viewers conj {:pred pred :render render}))

(defn clove/clerk-table-row [tag cells]
  (str "<tr>" (str/join "" (map (fn [c] (str "<" tag ">" (clerk/escape (clerk/pr-bounded c)) "</" tag ">")) cells)) "</tr>"))

;; map → two-column key/value table
(clerk/register-viewer!
  (fn [v] (map? v))
  (fn [m]
    (str "<table>"
         (str/join "" (map (fn [[k v]] (clove/clerk-table-row "td" [k v])) (seq m)))
         "</table>")))

;; seq of same-keyed maps → 2D table
(clerk/register-viewer!
  (fn [v] (and (sequential? v) (seq v) (every? map? v)))
  (fn [rows]
    (let [cols (keys (first rows))]
      (str "<table>"
           (clove/clerk-table-row "th" cols)
           (str/join "" (map (fn [r] (clove/clerk-table-row "td" (map (fn [k] (get r k)) cols))) rows))
           "</table>"))))

;; string that looks like markup → raw html (charts, svg, images)
(clerk/register-viewer!
  (fn [v] (and (string? v) (str/starts-with? v "<")))
  (fn [s] (str "<div class=\"raw\">" s "</div>")))

;; Bound a possibly-infinite/huge seq before printing — realize at most 100
;; elements so an infinite lazy seq (e.g. (iterate inc 0)) can't hang the
;; render. (count (take 101 v)) realizes ≤101 elements, never the whole seq.
(defn clerk/long-seq? [v]
  (and (sequential? v) (> (count (take 101 v)) 100)))

(defn clerk/pr-bounded [v]
  (if (clerk/long-seq? v)
    (str "(" (str/join " " (map clerk/pr-bounded (take 100 v))) " …)")
    (pr-str v)))

(defn clerk/render-value [v]
  (if (clerk/long-seq? v)
    (str "<pre class=\"value\">" (clerk/escape (clerk/pr-bounded v)) "</pre>")
    (or (some (fn [{:keys [pred render]}]
                (when (try (pred v) (catch Throwable e nil)) (render v)))
              (reverse @clerk/viewers))
        (str "<pre class=\"value\">" (clerk/escape (pr-str v)) "</pre>"))))

;; (clerk/html "<svg>...</svg>") — explicit raw-html marker for notebooks
(defn clerk/html [s] (str s))

;; ── cell + page rendering ──

(defn clerk/render-md [c]
  (str "<section class=\"cell md\">" (clerk/md->html (:text c)) "</section>"))

(defn clerk/render-code
  "Render an ALREADY-evaluated code cell (result r) to HTML."
  [c r]
  (let [dirs (:dirs c)]
    (str "<section class=\"cell code" (when (:error r) " bad") "\">"
         (when-not (get dirs "hide-code")
           (str "<pre class=\"src\">" (clerk/hl (:text c)) "</pre>"))
         (when (seq (:out r))
           (str "<pre class=\"out\">" (clerk/escape (:out r)) "</pre>"))
         (cond
           (:error r) (str "<pre class=\"err\">" (clerk/escape (:error r)) "</pre>")
           (get dirs "hide-result") ""
           (nil? (:value r)) ""
           (fn? (:value r)) ""          ; defn cells: the source is the story
           :else (clerk/render-value (:value r)))
         "</section>")))

(defn clerk/render-cell
  "Render a single cell with no caching (md, or eval+render a code cell)."
  [c]
  (if (= (:kind c) :md)
    (clerk/render-md c)
    (clerk/render-code c (clerk/eval-cell (:text c)))))

(def clove/clerk-css "
:root {
  --forest:   #364c3b;
  --fern:     #7b855e;
  --moss:     #989c6c;
  --bulb:     #e8e1ca;
  --tan:      #c7b697;
  --soil:     #625f49;
  --paper:    #f6f7f1;
  --surface:  #ecebdf;
  --ink:      #47422f;
  --ink-soft: #6b6650;
  --rule:     #d6d3c2;
  --link:     #364c3b;
  --accent:   #989c6c;
  --err:      #9c3b2e;
  --err-bg:   #f4e4dc;
  --hl-com:   #6f6b55;
  --hl-str:   #80552f;
  --hl-kwd:   #2f6650;
  --hl-lit:   #5f6e2c;
  --hl-pun:   #6e6953;
  --hl-spe:   #364c3b;
  --font-head: Comfortaa, 'Varela Round', Nunito, ui-rounded, system-ui, sans-serif;
  --font-body: 'Source Serif 4', 'Source Serif Pro', Georgia, serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', Inconsolata, ui-monospace, monospace;
}
@media (prefers-color-scheme: dark) {
  :root {
    --paper:    #1a201b;
    --surface:  #232b24;
    --ink:      #e6e2d3;
    --ink-soft: #b3ae99;
    --rule:     #3a443a;
    --link:     #b4c98f;
    --accent:   #7b855e;
    --err:      #f0a090;
    --err-bg:   #3a2622;
    --hl-com:   #9a9681;
    --hl-str:   #dcbc8c;
    --hl-kwd:   #8ec7a6;
    --hl-lit:   #c3d08c;
    --hl-pun:   #a29f8a;
    --hl-spe:   #b9d08f;
  }
}
body{background:var(--paper);color:var(--ink);margin:0;
     font:17px/1.65 var(--font-body)}
main{max-width:760px;margin:2.5rem auto;padding:0 1.2rem}
h1,h2,h3,h4{font-family:var(--font-head);font-weight:700;line-height:1.25;color:var(--ink)}
h1{font-size:2rem;margin:.2rem 0 1rem}
h2{font-size:1.45rem;margin-top:2.2rem}
h1 code,h2 code,h3 code{font-size:.85em}
a{color:var(--link);text-decoration-color:var(--accent);text-underline-offset:.18em}
a:hover{text-decoration-thickness:2px}
pre,code,textarea{font:13.5px/1.55 var(--font-mono);font-variant-ligatures:none;font-feature-settings:'liga' 0,'calt' 0}
p>code,li>code{background:var(--surface);padding:.05em .3em;border-radius:4px}
.cell{margin:1.1rem 0}
pre{overflow-x:auto;margin:0}
.src{background:var(--surface);padding:.7rem .95rem;border-radius:8px}
.out{border-left:3px dashed var(--rule);padding:.25rem .95rem;color:var(--ink-soft);margin-top:.45rem}
.err{border-left:3px solid var(--err);background:var(--err-bg);color:var(--err);
     padding:.5rem .95rem;margin-top:.45rem;border-radius:0 6px 6px 0}
.value{border-left:3px solid var(--accent);padding:.25rem .95rem;margin-top:.45rem}
.raw{margin-top:.45rem}
table{border-collapse:collapse;margin-top:.45rem;font:13.5px/1.5 var(--font-mono)}
td,th{border:1px solid var(--rule);padding:.22rem .6rem;text-align:left}
th{background:var(--surface);color:var(--link);font-weight:600}
.md table{font:15px/1.5 var(--font-body)}
blockquote{margin:1rem 0;padding:.1rem 1rem;border-left:3px solid var(--accent);color:var(--ink-soft)}
hr{border:0;border-top:1px solid var(--rule);margin:2rem 0}
.com{color:var(--hl-com);font-style:italic}.str{color:var(--hl-str)}.kwd{color:var(--hl-kwd)}
.lit{color:var(--hl-lit)}.pun{color:var(--hl-pun)}.spe{color:var(--hl-spe);font-weight:600}
footer.clove{max-width:760px;margin:3rem auto 2rem;padding:0 1.2rem;display:flex;align-items:center;
  gap:.45rem;font:13px var(--font-head);color:var(--ink-soft)}
footer.clove svg{width:20px;height:20px}
footer.clove a{color:inherit;text-decoration:none}
@media (max-width:600px){body{font-size:16px}main{margin-top:1.5rem}}
")

;; Brand mark (brand/mark.svg, ids prefixed) for the page footer.
(def clove/clerk-mark "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\" aria-hidden=\"true\"> <defs> <linearGradient id=\"clove-ring\" x1=\"0.15\" y1=\"0.1\" x2=\"0.85\" y2=\"0.9\"> <stop offset=\"0\" stop-color=\"#7b855e\"/> <stop offset=\"0.55\" stop-color=\"#4a6146\"/> <stop offset=\"1\" stop-color=\"#364c3b\"/> </linearGradient> <linearGradient id=\"clove-bulb\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0.3\"> <stop offset=\"0\" stop-color=\"#f1ecdb\"/> <stop offset=\"1\" stop-color=\"#d8ccae\"/> </linearGradient> <clipPath id=\"clove-bulbclip\"> <path id=\"clove-bulbpath\" d=\"M94 18 C92 32 88 44 76 54 C52 70 30 92 30 120 C30 148 50 168 74 172 L126 172 C150 168 170 148 170 120 C170 92 148 70 124 54 C112 44 108 32 106 18 C102 14 97 14 94 18 Z\"/> </clipPath> </defs> <circle cx=\"100\" cy=\"100\" r=\"72\" fill=\"#989c6c\"/> <circle cx=\"100\" cy=\"100\" r=\"85\" fill=\"none\" stroke=\"url(#clove-ring)\" stroke-width=\"10\"/> <use href=\"#clove-bulbpath\" fill=\"url(#clove-bulb)\"/> <g clip-path=\"url(#clove-bulbclip)\"> <rect x=\"20\" y=\"160\" width=\"160\" height=\"20\" fill=\"#625f49\"/> <path d=\"M92 52 C70 82 64 132 80 172 M108 52 C130 82 136 132 120 172\" fill=\"none\" stroke=\"#b9b48a\" stroke-width=\"2.5\"/> </g> <use href=\"#clove-bulbpath\" fill=\"none\" stroke=\"#47422f\" stroke-width=\"5\" stroke-linejoin=\"round\"/> <path d=\"M70 98 C62 110 62 130 70 142 M130 98 C138 110 138 130 130 142\" fill=\"none\" stroke=\"#364c3b\" stroke-width=\"6\" stroke-linecap=\"round\"/> <path d=\"M87 92 C94 91 97 96 100 103 L114 140 M102 112 L87 140\" fill=\"none\" stroke=\"#47422f\" stroke-width=\"6.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/> </svg>")

(def clove/clerk-footer
  (str "<footer class=\"clove\">" clove/clerk-mark
       "<span>made with <a href=\"https://alexalemi.github.io/clove/\">clove</a></span></footer>"))

(def clove/clerk-katex
  (str "<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/katex@0.16.10/dist/katex.min.css\">"
       "<script defer src=\"https://cdn.jsdelivr.net/npm/katex@0.16.10/dist/katex.min.js\"></script>"
       "<script defer src=\"https://cdn.jsdelivr.net/npm/katex@0.16.10/dist/contrib/auto-render.min.js\" "
       "onload=\"renderMathInElement(document.body,{delimiters:[{left:'$$',right:'$$',display:true},{left:'$',right:'$',display:false}]})\"></script>"))

(def clove/clerk-sse-js
  "<script>new EventSource('/events').onmessage=function(e){if(e.data==='reload')location.reload()};</script>")

;; ── standalone pages (-S): clove compiled to wasm ──
;; The interpreter is generic (no notebook inside it): the page hands it this
;; file's source, then calls clerk/wasm-cell per cell. So one build serves
;; every notebook, and can be hosted once and referenced with --wasm-url.

;; ASYNCIFY: the GC's wasm stack scan (see gc_collect). TOTAL_STACK: the
;; shadow stack clove's recursion guard measures; 1.5 MB makes the guard trip
;; (~1000 frames) before a browser's ~1 MB engine stack overflows, which
;; would kill the instance instead of raising a catchable error.
(def clerk/wasm-flags
  (str "-O2 -DCLOVE_NO_MAIN -sASYNCIFY=1 -sASYNCIFY_STACK_SIZE=8388608"
       " -sTOTAL_STACK=1572864 -sINITIAL_MEMORY=33554432 -sALLOW_MEMORY_GROWTH=1"
       " -sSINGLE_FILE=1 -sMODULARIZE=1 -sEXPORT_NAME=CloveWasm -sENVIRONMENT=web,node"
       " -sEXPORTED_FUNCTIONS=_clove_wasm_eval,_clove_wasm_last_error"
       " -sEXPORTED_RUNTIME_METHODS=ccall,ENV -lm"))

(defn clerk/find-battery
  "[dir content] of a file shipped with clove: a source checkout's cwd, the
  directory holding the binary, then the install's share dir."
  [name]
  (some (fn [d] (when d (when-let [c (clove/slurp-maybe (str d "/" name))] [d c])))
        ["." (clove/exe-dir*) (clove/exe-sharedir*) (clove/sharedir*)]))

(defn clerk/wasm-js
  "The interpreter as one JS file (wasm inlined), built with emcc on first
  use and cached per clove.c + flags + compiler."
  []
  (let [[dir src] (or (clerk/find-battery "clove.c")
                      (throw (ex-info "notebook -S: clove.c not found (looked in ., the binary's dir, and the share dir)" {})))
        emcc (or (clove/env* "CLOVE_EMCC") "emcc")
        out (str (clove/user-tmp-dir*) "/clove-wasm-" (Math/abs (hash (str src clerk/wasm-flags emcc))) ".js")]
    ;; test -O: the file gets inlined into pages, so never trust one this
    ;; user does not own
    (when-not (zero? (:exit (sh (str "test -O " out))))
      (println "clerk: compiling clove to wasm (once per clove version)...")
      (let [r (sh (str emcc " " clerk/wasm-flags " -o " out ".tmp.js " dir "/clove.c 2>&1"
                       " && mv " out ".tmp.js " out))]
        (when-not (zero? (:exit r))
          (throw (ex-info (str "notebook -S: emcc failed (is emscripten installed? set CLOVE_EMCC to its emcc)\n"
                               (:out r)) {})))))
    (slurp out)))

(def clove/clerk-live-css "
.clerk-live{position:relative}
.clerk-live pre.src{cursor:text}
.clerk-live.running section{opacity:.5}
.clerk-run{position:absolute;top:.35rem;right:.35rem;border:0;background:none;
  color:var(--ink-soft);cursor:pointer;font:12px var(--font-head);opacity:.45;padding:.2rem .45rem;border-radius:4px}
.clerk-live:hover .clerk-run{opacity:1}
.clerk-run:hover{background:var(--paper);color:var(--link)}
.clerk-ed{display:block;width:100%;box-sizing:border-box;resize:vertical;
  background:var(--surface);border:1px solid var(--accent);border-radius:8px;padding:.7rem .95rem;
  font:13.5px/1.55 var(--font-mono);color:var(--ink);outline:none}
.clerk-ed:focus{box-shadow:0 0 0 3px color-mix(in srgb,var(--accent) 35%,transparent)}
#clerk-bar{position:fixed;bottom:.8rem;right:.8rem;display:flex;gap:.6rem;align-items:center;
  background:var(--surface);border:1px solid var(--rule);border-radius:8px;padding:.3rem .7rem;
  font:12px/1.4 var(--font-head);color:var(--ink-soft)}
#clerk-bar button{font:inherit;cursor:pointer;color:var(--link);background:var(--paper);
  border:1px solid var(--rule);border-radius:5px;padding:.1rem .5rem}
#clerk-bar button:disabled{opacity:.5;cursor:default}
")

;; Written without backslashes or double quotes so it can live in this
;; string as-is; JSON.stringify makes the clove string literals (clove's
;; reader takes every JSON escape).
(def clove/clerk-live-js "
(function(){
var M=null,dead=false,q=Promise.resolve(),pending=null,booting=null;
var lazy=document.body.hasAttribute('data-clerk-lazy');
var cells=[].slice.call(document.querySelectorAll('.clerk-live'));
var bar=document.createElement('div');bar.id='clerk-bar';
var msg=document.createElement('span');
var all=document.createElement('button');all.textContent='run all';all.disabled=true;
bar.appendChild(msg);bar.appendChild(all);
if(cells.length)document.body.appendChild(bar);
function say(t){msg.textContent=t}
function die(e){dead=true;say('interpreter stopped ('+(e&&e.message||e)+'): reload the page to restart');
  if(pending){pending(e);pending=null}}
window.addEventListener('error',function(ev){if(pending)die(ev.error||ev.message)});
window.addEventListener('unhandledrejection',function(ev){if(pending)die(ev.reason)});
function call(src){
  var p=q.then(function(){return new Promise(function(res,rej){
    if(dead){rej(new Error('interpreter stopped; reload the page'));return}
    pending=rej;
    var r;
    try{r=M.ccall('clove_wasm_eval','string',['string'],[src],{async:true})}
    catch(e){die(e);return}
    r.then(function(v){pending=null;
      if(v===null)rej(new Error(M.ccall('clove_wasm_last_error','string',[],[])));
      else res(v)},function(e){die(e)});
  })});
  q=p.catch(function(){});
  return p;
}
function boot(){
  if(booting)return booting;
  if(typeof CloveWasm!=='function'){say('interpreter failed to load');return Promise.reject(new Error('no interpreter'))}
  say('starting interpreter...');
  var el=document.getElementById('clerk-lib');
  booting=CloveWasm().then(function(m){M=m;
      return el?el.value:fetch(document.body.getAttribute('data-clerk-lib')).then(function(r){
        if(!r.ok)throw new Error('clerk.clj: HTTP '+r.status);return r.text()})})
    .then(call)
    .then(function(){say('live: click code to edit, shift+enter runs it');all.disabled=false},
          function(e){if(!dead)die(e);throw e});
  return booting;
}
function cellText(cell){var ed=cell.querySelector('.clerk-ed');
  if(ed)cell.querySelector('.clerk-t').value=ed.value;
  return cell.querySelector('.clerk-t').value}
function edit(cell){
  var src=cell.querySelector('section pre.src');if(!src||dead)return;
  var ta=document.createElement('textarea');ta.className='clerk-ed';ta.spellcheck=false;
  ta.value=cell.querySelector('.clerk-t').value;
  function fit(){ta.style.height='auto';ta.style.height=(ta.scrollHeight+2)+'px'}
  ta.oninput=fit;
  ta.onkeydown=function(e){if(e.key==='Enter'&&(e.shiftKey||e.ctrlKey||e.metaKey)){e.preventDefault();run(cell)}};
  src.replaceWith(ta);fit();ta.focus();
}
function wire(cell){
  var src=cell.querySelector('section pre.src');
  if(src)src.addEventListener('click',function(){edit(cell)});
}
function show(cell,html){
  var t=document.createElement('div');t.innerHTML=html;
  cell.querySelector('section').replaceWith(t.firstElementChild);
  var ed=cell.querySelector('.clerk-ed');if(ed)ed.remove();
  wire(cell);
}
function fail(cell,e){
  var sec=cell.querySelector('section'),pre=document.createElement('pre');
  pre.className='err';pre.textContent=String(e&&e.message||e);
  sec.querySelectorAll('.err,.value,.out,.raw,table').forEach(function(n){n.remove()});
  sec.appendChild(pre);
}
function exec(cell){
  var text=cellText(cell),dirs=cell.querySelector('.clerk-d').value;
  cell.classList.add('running');
  return call('(clerk/wasm-cell '+JSON.stringify(text)+' '+dirs+')')
    .then(function(html){show(cell,html)},function(e){fail(cell,e)})
    .then(function(){cell.classList.remove('running')});
}
function upTo(cell){
  return cells.slice(0,cells.indexOf(cell)).reduce(function(p,c){
    return p.then(function(){if(!dead)return exec(c)})},Promise.resolve());
}
function run(cell){
  if(dead)return Promise.resolve();
  if(M)return exec(cell);
  cell.classList.add('running');
  return boot().then(function(){return upTo(cell)}).then(function(){return exec(cell)},function(){});
}
function runAll(){
  all.disabled=true;say('running all cells...');
  return cells.reduce(function(p,c){return p.then(function(){if(!dead)return exec(c)})},Promise.resolve())
    .then(function(){if(!dead){all.disabled=false;say('live: click code to edit, shift+enter runs it')}});
}
all.onclick=function(){boot().then(runAll)};
cells.forEach(function(cell){
  var b=document.createElement('button');b.className='clerk-run';b.textContent='run';
  b.title='run this cell (shift+enter while editing)';
  b.onclick=function(){run(cell)};cell.appendChild(b);wire(cell)});
window.clerkLive={boot:boot,call:call};
if(lazy)say('click code to edit it, or run to try it here');
else if(cells.length)boot().then(runAll,function(){});
})();
")

(defn clerk/standalone-scripts [opts]
  (let [[_ lib] (or (clerk/find-battery "clerk.clj")
                    (throw (ex-info "notebook -S: clerk.clj not found" {})))
        url (:wasm-url opts)]
    (str "<textarea id=\"clerk-lib\" hidden>\n" (clerk/escape lib) "</textarea>"
         (if url
           (str "<script src=\"" (str/replace (clerk/escape url) "\"" "&quot;") "\"></script>")
           ;; "</script" can't appear inside an inline script; "<\/" is the
           ;; same text to JS
           (str "<script>" (str/replace (clerk/wasm-js) "</script" "<\\/script") "</script>"))
         "<script>" clove/clerk-live-js "</script>")))

(defn clerk/page
  ([title cells-html live?] (clerk/page title cells-html live? {}))
  ([title cells-html live? opts]
   (str "<!doctype html><html><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>" (clerk/escape title) "</title>"
        "<style>" clove/clerk-css (if (:standalone opts) clove/clerk-live-css "") "</style>"
        clove/clerk-katex
        "</head><body><main>" cells-html "</main>" clove/clerk-footer
        (if live? clove/clerk-sse-js "")
        (if (:standalone opts) (clerk/standalone-scripts opts) "")
        "</body></html>")))

(defn clerk/live-wrap
  "Standalone pages: keep a code cell's source and directives beside its
  rendered section so the page's script can re-run it. Textareas because
  their content is raw text (only </textarea ends it), and a parser drops one
  leading newline, hence the explicit one."
  [c html]
  (str "<div class=\"clerk-live\"><textarea class=\"clerk-t\" hidden>\n"
       (clerk/escape (:text c)) "</textarea><textarea class=\"clerk-d\" hidden>\n"
       (clerk/escape (pr-str (or (:dirs c) {}))) "</textarea>" html "</div>"))

(defn clerk/wasm-cell
  "Browser entry point (standalone pages): eval one cell, return its HTML."
  [text dirs]
  (clerk/render-code {:kind :code :text text :dirs dirs} (clerk/eval-cell text)))

(defn clerk/render-cells
  "The cells of the notebook at path, rendered: an HTML string for <main>."
  [path opts]
  ;; Walk cells in order, threading the prior code cells' {:defs :key}. Each
  ;; code cell's eval is cached by a key folding in its text + the keys of the
  ;; earlier cells it depends on, so a save re-runs only changed cells and
  ;; their downstream dependents (clerk/eval-cached). Markdown re-renders each
  ;; time — it's cheap and pure.
  (let [cells (clerk/parse (slurp path))]
    (loop [cs cells, priors [], acc []]
      (if (empty? cs)
        (str/join "" acc)
        (let [c (first cs)]
          (if (= (:kind c) :md)
            (recur (rest cs) priors (conj acc (clerk/render-md c)))
            (let [{:keys [defs refs]} (clerk/cell-analyze (:text c))
                  rname (clerk/simple-def-name (:text c))
                  dep-keys (->> priors
                                (filter (fn [p] (some (fn [r] (contains? (:defs p) r)) refs)))
                                (mapv :key))
                  k (clerk/cell-key (:text c) dep-keys)
                  cached (@clerk/eval-cache k)
                  ev (fn [] (let [res (clerk/eval-cell (:text c))]
                              (swap! clerk/eval-cache assoc k res) res))
                  r (cond
                      ;; cached simple def: re-assert its binding cheaply (no
                      ;; recompute), keeping the env in cell order — so a later
                      ;; redef wins, and removing a redef falls back correctly
                      (and cached rname) (do (clerk/replay-def! rname (:value cached)) cached)
                      ;; macro/type/multi-form def: can't replay by value, so
                      ;; recompute to keep the env correct (these are cheap)
                      (and (seq defs) (not rname)) (ev)
                      ;; cached pure expression: reuse (it has no env effect)
                      cached cached
                      ;; cache miss: evaluate and store
                      :else (ev))]
              (recur (rest cs)
                     (conj priors {:defs defs :key k})
                     (conj acc (if (:standalone opts)
                                 (clerk/live-wrap c (clerk/render-code c r))
                                 (clerk/render-code c r)))))))))))

(defn clerk/wasm-repl
  "Browser REPL entry: eval src, return printed output then the value, or the
  error message."
  [src]
  (let [{:keys [value error out]} (clerk/eval-cell src)]
    (str out (if error (str "error: " error) (pr-str value)))))

(defn clerk/render-file
  ([path live?] (clerk/render-file path live? {}))
  ([path live? opts]
   (clerk/page path (clerk/render-cells path opts) live? opts)))

;; ── static build ──

(defn clerk/build
  ([path out] (clerk/build path out {}))
  ([path out opts]
   (spit out (clerk/render-file path false opts))
   out))

;; ── live server: single-threaded HTTP + SSE ──
;; The 250ms accept timeout doubles as the file-watch tick; SSE clients are
;; kept as raw fds and receive \"data: reload\" when the notebook changes.

(defn clove/clerk-respond [fd status ctype body]
  (tcp/send fd (str "HTTP/1.1 " status
                    "\r\nContent-Type: " ctype
                    "\r\nContent-Length: " (clove/str-nbytes* body)
                    "\r\nConnection: close\r\n\r\n" body))
  (tcp/close fd))

(defn clove/clerk-handle
  "Serve one request; returns the (possibly grown) SSE client vector."
  [fd html sse]
  (let [req (or (tcp/recv fd) "")
        m (re-find #"^GET (\S+)" req)
        path (if m (second m) nil)]
    (cond
      (= path "/events")
      (do (tcp/send fd (str "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream"
                            "\r\nCache-Control: no-cache\r\nConnection: keep-alive"
                            "\r\n\r\nretry: 500\n\n"))
          (conj sse fd))
      (= path "/")
      (do (clove/clerk-respond fd "200 OK" "text/html; charset=utf-8" html) sse)
      :else
      (do (clove/clerk-respond fd "404 Not Found" "text/plain" "not found") sse))))

(defn clove/clerk-render-safe [path old-html]
  (try (clerk/render-file path true)
       (catch Throwable e
         (println "clerk: render failed:" (ex-message e))
         old-html)))

(defn clove/clerk-broadcast
  "Send a reload to every SSE client; returns the survivors."
  [sse]
  (filterv (fn [c] (tcp/send c "data: reload\n\n")) sse))

(defn clove/clerk-serve-file [path port]
  (let [srv (tcp/listen port)]
    (println (str "clerk: " path " → http://127.0.0.1:" port "  (ctrl-c to stop)"))
    (loop [html (clove/clerk-render-safe path "") mt (clove/mtime* path) sse []]
      (let [fd (tcp/accept srv 250)
            sse (if fd (clove/clerk-handle fd html sse) sse)
            nmt (clove/mtime* path)]
        (if (and nmt (not= nmt mt))
          (recur (clove/clerk-render-safe path html) nmt (clove/clerk-broadcast sse))
          (recur html mt sse))))))

;; ── directory mode: watch the whole tree, show whichever file changed ──

(defn clove/clerk-walk
  "All .clj files under dir, recursive; skips dot-directories."
  [dir]
  (mapcat (fn [n]
            (let [p (str dir "/" n)]
              (cond
                (str/starts-with? n ".") []
                (clove/dir?* p) (clove/clerk-walk p)
                (str/ends-with? n ".clj") [p]
                :else [])))
          (or (clove/list-dir* dir) [])))

(defn clove/clerk-snapshot
  "Map of path → mtime for every .clj under dir."
  [dir]
  (reduce (fn [m p] (assoc m p (clove/mtime* p))) {} (clove/clerk-walk dir)))

(defn clove/clerk-changed
  "Some path that is new or has a different mtime than in old, else nil."
  [old new]
  (some (fn [[p t]] (when (not= t (get old p)) p)) (seq new)))

(defn clove/clerk-newest [snap]
  (first (last (sort-by (fn [[_ t]] t) (seq snap)))))

(defn clove/clerk-serve-dir [dir port]
  (let [srv (tcp/listen port)
        snap (clove/clerk-snapshot dir)
        cur (clove/clerk-newest snap)
        empty-page (clerk/page dir "<p>no .clj files here yet — save one and it will appear.</p>" true)]
    (println (str "clerk: " dir "/ (" (count snap) " files) → http://127.0.0.1:" port "  (ctrl-c to stop)"))
    (when cur (println (str "clerk: showing " cur)))
    (loop [cur cur
           html (if cur (clove/clerk-render-safe cur empty-page) empty-page)
           snap snap sse []]
      (let [fd (tcp/accept srv 250)
            sse (if fd (clove/clerk-handle fd html sse) sse)
            nsnap (clove/clerk-snapshot dir)
            chg (clove/clerk-changed snap nsnap)]
        (if chg
          (do (when (not= chg cur) (println (str "clerk: showing " chg)))
              (recur chg (clove/clerk-render-safe chg html) nsnap (clove/clerk-broadcast sse)))
          (recur cur html nsnap sse))))))

(defn clerk/serve
  "Serve a notebook file, or a directory — in directory mode every .clj in
  the tree is watched and saving any of them switches the view to it."
  ([path] (clerk/serve path 7878))
  ([path port]
   (if (clove/dir?* path)
     (clove/clerk-serve-dir path port)
     (clove/clerk-serve-file path port))))

;; ── CLI entry (invoked by `clove clerk ...`) ──

(defn clerk/parse-args
  "*args* → {:file :port :out :standalone :wasm-url :wasm}; flags in any order."
  [args]
  (loop [as (seq args) m {}]
    (if-let [a (first as)]
      (case a
        "-o"         (recur (nnext as) (assoc m :out (second as)))
        "-S"         (recur (next as) (assoc m :standalone true))
        "--wasm-url" (recur (nnext as) (assoc m :wasm-url (second as)))
        "--wasm"     (recur (nnext as) (assoc m :wasm (second as)))
        (recur (next as) (if (:file m) (assoc m :port a) (assoc m :file a))))
      m)))

(def clerk/usage
  (str "usage: clove notebook <file.clj | dir> [port]       live server\n"
       "       clove notebook <file.clj> -o out.html [-S [--wasm-url URL]]\n"
       "       clove notebook --wasm clove-wasm.js           write the wasm interpreter"))

(defn clerk/main []
  (let [{:keys [file port out standalone wasm-url wasm]} (clerk/parse-args *args*)
        standalone (or standalone (some? wasm-url))]   ; --wasm-url implies -S
    (cond
      wasm (do (spit wasm (clerk/wasm-js)) (println "clerk: wrote" wasm))
      (nil? file) (println clerk/usage)
      out (println "clerk: wrote" (clerk/build file out {:standalone standalone :wasm-url wasm-url}))
      (or standalone wasm-url) (throw (ex-info (str "notebook: -S needs -o out.html\n" clerk/usage) {}))
      :else (clerk/serve file (or (when port (parse-long port)) 7878)))))
