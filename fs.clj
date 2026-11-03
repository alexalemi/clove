;; fs.clj — babashka.fs-flavored filesystem battery.
;; (load-file "fs.clj") or (require 'fs). Flat names: fs/exists? etc.
;; Built on clove's file natives: no shell, no FFI.

(defn fs/exists? [p] (clove/file-op* :exists p))
(defn fs/directory? [p] (clove/dir?* p))
(defn fs/regular-file? [p] (clove/file-op* :file? p))
(defn fs/list-dir
  "Names in directory p, excluding . and .." [p]
  (let [names (clove/list-dir* p)]
    (when (nil? names) (throw (ex-info (str "fs/list-dir: cannot open " p) {})))
    (sort names)))
(defn fs/create-dir
  "Create p and any missing parents; true when p is a directory after." [p]
  (boolean (or (clove/dir?* p) (clove/file-op* :mkdirs p))))
(defn fs/delete [p] (clove/file-op* :delete p))
(defn fs/move [src dst] (clove/file-op* :rename src dst))
(defn fs/copy [src dst]
  (zero? (:exit (clojure.core/sh "cp" src dst))))
(defn fs/file-name [p] (last (str/split p "/")))
(defn fs/parent [p]
  (let [parts (butlast (str/split p "/"))]
    (when (seq parts) (str/join "/" parts))))
(defn fs/extension [p]
  (let [n (fs/file-name p)
        parts (str/split n ".")]
    (when (> (count parts) 1) (last parts))))
(defn fs/temp-dir [] (or (clove/env* "TMPDIR") "/tmp"))
(defn fs/cwd [] (clove/file-op* :abs "."))
