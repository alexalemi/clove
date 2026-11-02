;; gen-inputs.clj: writes input/01.txt ... input/06.txt for p01-p06.
;;
;; Advent of Code asks people not to republish their puzzle inputs, so these
;; are made up. Each one has the same shape and roughly the same size as a
;; real input, and gives the benchmark the same amount of work. A fixed-seed
;; LCG keeps them identical on every machine and runtime:
;;
;;   cd benchmarks/aoc && clove gen-inputs.clj

(def state (atom 20261102))

(defn rnd
  "A pseudo-random integer in [0, n)."
  [n]
  (swap! state (fn [s] (mod (+ (* s 1103515245) 12345) 2147483648)))
  (mod (quot @state 65536) n))

;; 01: one long line of digits. Neighbors match about one time in ten, as in
;; a real input.
(defn digits [n]
  (apply str (repeatedly n #(rnd 10))))

;; 04: lines of short lowercase words. Some lines repeat a word, and some
;; contain two anagrams, so neither part's answer is trivial.
(def letters "abcdefghijklmnopqrstuvwxyz")

(defn word []
  (apply str (repeatedly (+ 2 (rnd 6)) #(nth letters (rnd 26)))))

(defn shuffle-vec
  "A Fisher-Yates shuffle driven by rnd, so it's the same everywhere."
  [v]
  (loop [v (vec v) i (dec (count v))]
    (if (pos? i)
      (let [j (rnd (inc i)) t (v i)]
        (recur (assoc v i (v j) j t) (dec i)))
      v)))

(defn passphrase []
  (let [ws (vec (repeatedly (+ 3 (rnd 10)) word))
        ws (case (rnd 4)
             0 (conj ws (ws (rnd (count ws))))                           ; a repeated word
             1 (conj ws (apply str (shuffle-vec (ws (rnd (count ws)))))) ; an anagram
             ws)]
    (clojure.string/join " " (shuffle-vec ws))))

;; 05: jump offsets. Offset i points back up to i places, so the program
;; bounces around for a long time before it escapes; part 2 takes tens of
;; millions of steps.
(defn offsets [n]
  (for [i (range n)]
    (if (zero? (rnd 12)) (rnd 3) (- (rnd (inc i))))))

;; 06: sixteen memory banks. Most random starts repeat within a couple of
;; thousand redistributions; draw until one takes 4,000 to 6,000, the size
;; of a real input's.
(defn redistribute [banks]
  (let [n (count banks)
        m (apply max banks)
        i (.indexOf banks m)]
    (loop [b (assoc banks i 0) j (mod (inc i) n) k m]
      (if (zero? k) b (recur (update b j inc) (mod (inc j) n) (dec k))))))

(defn first-repeat [banks]
  (loop [b banks seen #{} c 0]
    (let [nb (redistribute b)]
      (if (seen nb) (inc c) (recur nb (conj seen nb) (inc c))))))

(defn banks []
  (first (filter #(<= 4000 (first-repeat %) 6000)
                 (repeatedly #(vec (repeatedly 16 (fn [] (rnd 16))))))))

(spit "input/01.txt" (str (digits 2000) "\n"))
(spit "input/04.txt" (str (clojure.string/join "\n" (repeatedly 512 passphrase)) "\n"))
(spit "input/05.txt" (str (clojure.string/join "\n" (offsets 1100)) "\n"))
(spit "input/06.txt" (str (clojure.string/join "\t" (banks)) "\n"))
(println "wrote input/01.txt input/04.txt input/05.txt input/06.txt")
