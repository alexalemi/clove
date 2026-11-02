;; # Notebooks
;;
;; A notebook is a `.clj` file. Comments at the top level are markdown and
;; everything else is code, shown with its value. Every page on this site is
;; one: this page is `doc/notebooks.clj` in the repo.
;;
;; ```sh
;; clove notebook nb.clj               # serve on localhost:7878, re-render on save
;; clove notebook nb.clj -o nb.html    # write a static page
;; clove notebook nb.clj -o nb.html -S # a page that runs its code in the browser
;; ```
;;
;; While serving, saving the file updates the browser. Cells that didn't
;; change, and don't depend on a cell that did, aren't run again.
;;
;; ## Cells
;;
;; By default each top-level form is a cell. Put a line with just `;; %%`
;; anywhere and the file switches to percent cells, as in jupytext: a cell
;; runs everything up to the next `;; %%` or the next comment block, and
;; shows only the last value. End a cell with `nil` to show nothing.
;; %%

(def temps-c [12.5 17.0 21.5 9.0])
(defn c->f [c] (+ 32 (* 9/5 c)))
(map c->f temps-c)

;; Two directives hide parts of the next cell: `;; @clerk:hide-code` and
;; `;; @clerk:hide-result`.
;;
;; ## Values
;;
;; A sequence of maps becomes a table. A string that starts with `<` is
;; treated as HTML, so a cell can draw.
;; %%

(let [w 300, h 60, n (count temps-c)]
  (str "<svg width='" w "' height='" h "'>"
       (apply str
              (map-indexed
               (fn [i t]
                 (str "<rect x='" (* i (/ w n)) "' y='" (- h (* 2.5 t))
                      "' width='" (- (/ w n) 6) "' height='" (* 2.5 t)
                      "' fill='#7b855e'/>"))
               temps-c))
       "</svg>"))

;; You can teach a notebook new kinds of value with
;; `clerk/register-viewer!`, which takes a predicate and a function returning
;; HTML.
;;
;; ## Pages that run
;;
;; With `-S`, the page carries clove compiled to WebAssembly. It reruns every
;; cell when it loads, and any cell can be edited and run again: click the
;; code, then shift+enter. The cells on this site work the same way.
;;
;; Building the interpreter needs emscripten's `emcc` once (set `CLOVE_EMCC`
;; if it isn't on your `PATH`). The result is cached. It adds 1.3 MB to each
;; page, so for more than a page or two, write it to a file once and point
;; the pages at it:
;;
;; ```sh
;; clove notebook --wasm clove-wasm.js
;; clove notebook nb.clj -o nb.html -S --wasm-url clove-wasm.js
;; ```
;;
;; The browser is a smaller world than your terminal. There are no
;; coroutines, so no `core.async`, `future` or agents; no `sh`, FFI, sockets
;; or files; and recursion stops at about 1000 frames.
