;; inspect.clj — a terminal browser for nested data.
;;
;;   (require '[inspect])
;;   (inspect/inspect {:users [{:name "ada"} {:name "bob"}]})
;;
;; ↑/↓ or k/j move · PgUp/PgDn page · g/G top/bottom · Enter/→/l open the
;; selected entry · ←/h/Backspace back up (the old selection comes back)
;; q/Esc quit, RETURNING the value being viewed — so (inspect x) doubles as
;; "dig out the piece I want" at the REPL. When stdout is not a terminal it
;; just pprints x and returns it.
;;
;; Shape: the screen is a pure (render state rows cols) → string and keys go
;; through a pure (step state key) → state; only `inspect` touches the
;; terminal. That makes everything but the loop testable without a tty.
;;
;; State: {:stack [frame ...] :rows r :cols c :done bool}, a frame being
;; {:val v :crumb "label" :sel i :scroll i}. Descending pushes a frame,
;; ascending pops one — the parent frame still holds its :sel, which is how
;; the selection is restored.
;;
;; Lazy/infinite seqs are never counted: every view realizes only the window
;; it shows (scroll + visible rows + 1, the +1 telling us whether there is
;; more). The same windowing keeps huge collections cheap.

;; ── values → entries ────────────────────────────────────────────────────────

(def preview-print-level 4)

;; One line of pr-str, cut to w columns with "…". print-length bounds the
;; work for big/infinite values: a line can't show more than w/2 items anyway.
(defn preview [x w]
  (let [s (binding [*print-length* (max 1 (quot w 2)) *print-level* preview-print-level]
            (pr-str x))
        s (str/replace s "\n" "\\n")]
    (cond (<= w 0) ""
          (<= (count s) w) s
          :else (str (subs s 0 (dec w)) "…"))))

(defn derefable? [v]
  (or (= :atom (type v)) (volatile? v) (delay? v)
      (contains? #{:CloveAgent :CloveRef} (type v))
      ;; an unrealized promise/future would block the UI on deref
      (and (contains? #{:ClovePromise :CloveFuture} (type v)) (realized? v))))

;; Strings (and scalars' printed form) wrap to the width, keeping blank lines.
(defn wrap-lines [s w]
  (let [w (max 1 w)]
    (mapcat (fn [line] (if (= "" line) [""] (map #(apply str %) (partition-all w line))))
            (str/split s #"\n" -1))))

;; Lazy seq of entries for v: {:label s :val x :crumb s} for children one can
;; open, {:text s} for display-only lines (string/scalar content).
(defn entries
  ([v cols] (entries v cols 0))
  ([v cols start]
   (let [w (max 1 (- cols 2))]
     (cond
       (map? v)        (map (fn [[k x]] {:label (preview k (max 8 (quot cols 3))) :val x
                                         :crumb (preview k 24)})
                            (drop start v))
       ;; vectors window in O(1) with subvec, so scrolling a million-element
       ;; vector does not walk it
       (vector? v)     (map-indexed (fn [i x] (let [i (+ start i)] {:label (str i) :val x :crumb (str i)}))
                                    (subvec v (min start (count v))))
       (sequential? v) (map-indexed (fn [i x] (let [i (+ start i)] {:label (str i) :val x :crumb (str i)}))
                                    (drop start v))
       (set? v)        (map (fn [x] {:label "" :val x :crumb (preview x 24)}) (drop start v))
       (derefable? v)  (drop start [{:label "@" :val (deref v) :crumb "@"}])
       (string? v)     (map (fn [l] {:text l}) (drop start (wrap-lines v w)))
       :else           (map (fn [l] {:text l}) (drop start (wrap-lines (pr-str v) w)))))))

;; Exact entry count when it is cheap and finite; nil for seqs (never count).
(defn known-count [v cols]
  (cond
    (or (map? v) (vector? v) (set? v)) (count v)
    (sequential? v) nil
    (derefable? v) 1
    :else (count (entries v cols))))   ; wrapped lines of a string/scalar

;; Index of the last entry at or before i (i itself when it exists).
(defn clamp-index [v cols i]
  (let [i (max 0 i)]
    (if-let [n (known-count v cols)]
      (max 0 (min i (dec n)))
      (max 0 (dec (count (take (inc i) (entries v cols))))))))

(defn type-name [v]
  (cond (nil? v) "nil"
        (record? v) (str "record " (name (type v)))
        :else (let [t (type v)] (if (keyword? t) (name t) (str t)))))

;; ── state ───────────────────────────────────────────────────────────────────

(defn init
  ([v] (init v 24 80))
  ([v rows cols] {:stack [{:val v :crumb "root" :sel 0 :scroll 0}] :rows rows :cols cols}))

(defn current [state] (:val (peek (:stack state))))

;; header line + footer line; the rest is the entry list
(defn body-rows [rows] (max 1 (- rows 2)))

(defn fix-scroll [frame h]
  (let [{:keys [sel scroll]} frame]
    (cond (< sel scroll) (assoc frame :scroll sel)
          (>= sel (+ scroll h)) (assoc frame :scroll (inc (- sel h)))
          :else frame)))

;; ── step: state × key → state ───────────────────────────────────────────────

(def keymap
  {"j" :down "\u001b[B" :down "\u001bOB" :down
   "k" :up   "\u001b[A" :up   "\u001bOA" :up
   "\u001b[6~" :pgdn " " :pgdn
   "\u001b[5~" :pgup
   "g" :top "\u001b[H" :top "G" :bottom "\u001b[F" :bottom
   "\r" :open "\n" :open "l" :open "\u001b[C" :open "\u001bOC" :open
   "h" :back "\u001b[D" :back "\u001bOD" :back "\u007f" :back "\b" :back
   "q" :quit "\u001b" :quit})

(defn move [state f]
  (let [{:keys [rows cols stack]} state
        fr (peek stack)
        sel (clamp-index (:val fr) cols (f (:sel fr) (body-rows rows)))]
    (assoc state :stack (conj (pop stack) (fix-scroll (assoc fr :sel sel) (body-rows rows))))))

(defn step [state key]
  (let [{:keys [stack cols]} state
        fr (peek stack)]
    (case (if (nil? key) :quit (get keymap key))
      :down   (move state (fn [s _] (inc s)))
      :up     (move state (fn [s _] (dec s)))
      :pgdn   (move state +)
      :pgup   (move state -)
      :top    (move state (fn [_ _] 0))
      ;; G on a seq realizes at most 10000 more entries — it may be infinite
      :bottom (move state (fn [s _] (if (known-count (:val fr) cols) Long/MAX_VALUE (+ s 10000))))
      :open   (let [e (first (entries (:val fr) cols (:sel fr)))]
                (if (and e (contains? e :val))
                  (assoc state :stack (conj stack {:val (:val e) :crumb (:crumb e) :sel 0 :scroll 0}))
                  state))
      :back   (if (> (count stack) 1) (assoc state :stack (pop stack)) state)
      :quit   (assoc state :done true)
      state)))

;; ── render: state × rows × cols → screen text ───────────────────────────────

(defn fit [s w]
  (cond (<= w 0) ""
        (<= (count s) w) s
        :else (str (subs s 0 (dec w)) "…")))

;; Breadcrumb keeps its tail when too long: the deepest part is what matters.
(defn crumbs [stack w]
  (let [s (str/join " > " (map :crumb stack))]
    (if (<= (count s) w) s (str "…" (subs s (- (count s) (dec w)))))))

(defn plural [n word]
  (str n " " (cond (= 1 n) word (= "entry" word) "entries" :else (str word "s"))))

(defn pad-right [s w] (str s (apply str (repeat (max 0 (- w (count s))) " "))))

(defn render [state rows cols]
  (let [{:keys [stack]} state
        h (body-rows rows)
        fr (fix-scroll (peek stack) h)
        {:keys [val sel scroll]} fr
        window (vec (take (inc h) (entries val cols scroll)))   ; h + 1: is there more?
        shown (take h window)
        n (known-count val cols)
        size (cond (not (or (coll? val) (string? val) (derefable? val))) nil
                   n (plural n (cond (map? val) "entry" (string? val) "line" :else "item"))
                   (> (count window) h) (str (+ scroll h) "+ items…")
                   :else (plural (+ scroll (count window)) "item"))
        info (str "  " (type-name val) (when size (str ", " size)))
        head (str (crumbs stack (max 10 (- cols (count info)))) info)
        lw (min (max 8 (quot cols 3)) (apply max 0 (map #(count (or (:label %) "")) shown)))
        line (fn [i e]
               (let [txt (if (contains? e :text)
                           (:text e)
                           (let [lbl (:label e)]
                             (if (= "" lbl)
                               (preview (:val e) (- cols 2))
                               (str (pad-right lbl lw) "  " (preview (:val e) (- cols lw 4))))))]
                 (if (= i sel)
                   (str "\u001b[7m" (pad-right (fit (str "> " txt) cols) cols) "\u001b[0m")
                   (fit (str "  " txt) cols))))
        body (map-indexed (fn [j e] (line (+ scroll j) e)) shown)
        foot (fit (str (inc sel) "/" (or n (if (> (count window) h) "?" (+ scroll (count window))))
                       "  ↑↓ move  ⏎ open  ← back  q quit")
                  cols)]
    (str/join "\n" (concat [(fit head cols)] body
                           (repeat (- h (count shown)) "")
                           [(str "\u001b[2m" foot "\u001b[0m")]))))

;; ── the terminal loop ───────────────────────────────────────────────────────

(defn inspect [x]
  (if-not (and (clove/isatty*) (clove/raw-mode* true))
    (do (pprint x) x)
    (try
      (print "\u001b[?1049h\u001b[?25l")
      (loop [st (init x)]
        (let [[r c] (clove/term-size*)
              st (assoc st :rows r :cols c)]
          ;; home + per-line clear-to-EOL instead of a full clear: no flicker
          (print (str "\u001b[H" (str/replace (render st r c) "\n" "\u001b[K\r\n") "\u001b[K\u001b[J"))
          (flush)
          (let [st (step st (clove/read-key*))]
            (if (:done st) (current st) (recur st)))))
      (finally
        (print "\u001b[?25h\u001b[?1049l")
        (flush)
        (clove/raw-mode* false)))))
