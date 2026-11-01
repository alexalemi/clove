(ns clojure.java.io
  "A pragmatic subset of clojure.java.io over clove's slurp/spit. `file`
   yields a java.io.File value ({:clove/type :File :path p}; str gives the
   path, and every path-taking fn accepts it). reader/writer/streams are the
   path (slurp/spit accept paths) or the StringReader/StringBuilder passed in."
  (:require [clojure.string :as str]))
;; file, as-file, make-parents, delete-file and copy are defined in clove's
;; prelude under these qualified names (so they work without a require).
(defn as-relative-path [x] (str x))
(defn as-url [x] (str x))
(defn reader
  "A line-reader over reader-ish things (StringReader., another reader, a
   StringBuilder); paths and Files stay path strings for slurp compatibility."
  [x & _]
  (if (and (map? x) (contains? #{:java.io.Reader :java.io.PushbackReader :LineReader :StringBuilder :clove/Stdin}
                               (:clove/type x)))
    (java.io.BufferedReader. x)
    (str x)))
(defn writer
  "A StringWriter/StringBuilder passes through (its .write/.append/.toString
   accumulate); anything else is a path string for spit."
  [x & _]
  (if (and (map? x) (= :StringBuilder (:clove/type x))) x (str x)))
(defn input-stream [x & _] (str x))
(defn output-stream [x & _] (str x))
(defn resource [_] nil)
