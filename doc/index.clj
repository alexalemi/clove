;; clove is Clojure, the language, reimplemented as a single C99 file with no
;; dependencies beyond libc. It has persistent collections, lazy seqs,
;; macros, destructuring, protocols, records, ratios, BigInts, namespaces and
;; a REPL, and it runs real libraries unmodified: DataScript, core.logic,
;; Emmy, instaparse, hiccup.
;;
;; ## Install
;;
;; ```sh
;; curl -fsSL https://raw.githubusercontent.com/alexalemi/clove/main/install.sh | sh
;; ```
;;
;; That builds the latest release from source (you need `cc` and `make`) and
;; puts `clove` in `~/.local/bin`. [Install](install.html) has the details.
;;
;; ## What it's for
;;
;; We love Clojure, and we wanted one we could drop into a C program, extend
;; with our own natives, and bend all the way down to the core library. clove
;; is that. It's also a decent scripting language: a script is a `.clj` file,
;; `#!/usr/bin/env clove` works, and `clove bundle` turns one into a
;; standalone binary you can hand to someone without Clojure.
;;
;; It is a hobby project, written mostly by an AI model and checked against
;; JVM Clojure. Please don't use it for anything that matters.
;; [How clove is made](how-clove-is-made.html) says more.
;;
;; ## A taste

(defn word-counts [text]
  (->> (re-seq #"[a-z']+" (str/lower-case text))
       frequencies
       (sort-by val >)
       (take 3)))

(word-counts "The cat and the hat and the bat")

;; Every code block on these pages runs in your browser too. Click one to edit
;; it, then shift+enter.
;;
;; ## Where next
;;
;; The [tutorial](tutorial.html) starts from nothing. If you already write
;; Clojure, [Coming from Clojure](from-clojure.html) lists what's different.
;; To put clove inside your own program, read [Embedding in C](embedding.html).
