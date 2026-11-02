;; # clove brand sheet
;;
;; The logo, palette and type for clove's docs, notebooks and standalone
;; pages. This page is itself a clove notebook: render it with
;; `clove notebook brand/brand.clj -o brand.html` from the repo root, next to
;; the SVGs. The colors below are read from `brand/palette.css`, the canonical
;; copy that `clerk.clj` inlines for every notebook.

;; %%
;; @clerk:hide-code
(defn tile [bg body]
  (str "<div style='background:" bg ";padding:1.4rem;border-radius:10px;"
       "display:flex;align-items:center;justify-content:center;flex:1'>" body "</div>"))

(str "<div style='display:flex;gap:1rem;flex-wrap:wrap'>"
     (tile "#f6f7f1" "<img src='logo.svg' alt='clove logo' style='height:190px'>")
     (tile "#1a201b" "<img src='logo-dark.svg' alt='clove logo, dark' style='height:190px'>")
     "</div><div style='display:flex;gap:1rem;margin-top:1rem;flex-wrap:wrap'>"
     (tile "#f6f7f1" "<img src='logo-horizontal.svg' alt='' style='height:54px'>")
     (tile "#1a201b" "<img src='logo-horizontal-dark.svg' alt='' style='height:54px'>")
     (tile "#ecebdf" (str "<img src='mark.svg' alt='' style='height:64px;margin-right:1rem'>"
                          "<img src='favicon.svg' alt='' style='height:32px;margin-right:.6rem'>"
                          "<img src='favicon.svg' alt='' style='height:16px'>"))
     "</div>")

;; ## Logo
;;
;; - `logo.svg` (stacked) for the home page and README; `logo-horizontal.svg`
;;   for headers. Each has a `-dark` twin with a light wordmark.
;; - `mark.svg` alone at 48px and up; `favicon.svg` (no parens or detail,
;;   heavier strokes) below that.
;; - The wordmark is Comfortaa Bold converted to outlines, so it renders the
;;   same without the font installed.
;; - Don't recolor the mark or put it on mid-tone backgrounds: it is drawn for
;;   paper and for the dark-mode page color.

;; ## Palette
;;
;; Logo colors first, then the roles pages use. For text colors, the two
;; numbers are WCAG contrast against the page (`--paper`) and against code
;; backgrounds (`--surface`), in light mode; text needs 4.5. Fills are never
;; used for text.

;; %%
;; @clerk:hide-code
(def css (slurp "brand/palette.css"))

(defn tokens
  "[name value] pairs of the custom properties in a block of css."
  [block]
  (mapv (fn [[_ n v]] [n v]) (re-seq #"--([a-z-]+):\s*(#[0-9a-f]{6})" block)))

(def dark-at (str/index-of css "@media"))
(def light (tokens (subs css 0 dark-at)))
(def dark (into {} (tokens (subs css dark-at))))

(defn luminance [hex]
  (let [ch (fn [i] (let [c (/ (Integer/parseInt (subs hex i (+ i 2)) 16) 255.0)]
                     (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (ch 1)) (* 0.7152 (ch 3)) (* 0.0722 (ch 5)))))

(defn contrast [a b]
  (let [[hi lo] (sort > [(luminance a) (luminance b)])]
    (/ (+ hi 0.05) (+ lo 0.05))))

(def text-roles
  "Tokens used as text color; the rest are fills and rules."
  #{"forest" "soil" "ink" "ink-soft" "link" "err" "hl-com" "hl-str" "hl-kwd" "hl-lit" "hl-pun" "hl-spe"})

(defn ratio [fg bg]
  (let [r (contrast fg bg)]
    (str "<span style='color:" (if (>= r 4.5) "var(--forest)" "var(--err)") "'>"
         (format "%.1f" r) "</span>")))

(def lightm (into {} light))

(defn swatch [[n v]]
  (str "<div style='width:150px'>"
       "<div style='height:58px;border-radius:8px;background:" v
       ";border:1px solid var(--rule)'></div>"
       "<div style='font:600 13px var(--font-head);margin-top:.35rem'>--" n "</div>"
       "<div style='font:12px var(--font-mono);color:var(--ink-soft)'>" v
       (when-let [d (dark n)] (str " · dark " d)) "</div>"
       "<div style='font:12px var(--font-mono);color:var(--ink-soft)'>"
       (if (text-roles n)
         (str (ratio v (lightm "paper")) " / " (ratio v (lightm "surface")))
         "fill")
       "</div></div>"))

(str "<div style='display:flex;flex-wrap:wrap;gap:1rem 1.1rem'>"
     (apply str (map swatch light)) "</div>")

;; ## Type
;;
;; Headings use **Comfortaa**, the rounded sans of the wordmark. Body text
;; is **Source Serif 4**, a reading face for long prose. Code is
;; **JetBrains Mono**. All three are OFL; the site will self-host them, and
;; until then each falls back to a close system face.

;; %%
;; @clerk:hide-code
(str "<div style='font-family:var(--font-head);font-weight:700;font-size:2.2rem;line-height:1.2'>"
     "Clojure in one C file</div>"
     "<div style='font-family:var(--font-body);font-size:1.1rem;margin:.6rem 0'>"
     "Embed it with one <code>#include</code>, bundle scripts into standalone "
     "binaries, or run it in the browser.</div>"
     "<div style='font-family:var(--font-mono);font-size:.95rem'>"
     "(->> (range 10) (filter odd?) (map #(* % %)))</div>")

;; ## Notebook elements
;;
;; Everything below is an ordinary cell, styled by the same palette.

;; %%
(def lisps
  [{:name "Clojure" :host "JVM" :since 2007}
   {:name "Fennel" :host "Lua" :since 2016}
   {:name "Janet" :host "C" :since 2017}])

(sort-by :since > lisps)

;; %%
(println "printed output appears above the value")
(frequencies (map #(mod % 3) (range 10)))

;; %%
(map (fn [n] (keyword (str "k" n))) (range 5))

;; %% errors stay in their cell
(nth [1 2 3] 10)
