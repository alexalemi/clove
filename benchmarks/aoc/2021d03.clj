(def nums (clojure.string/split-lines "10110\n01011\n11010\n10000\n01001\n10101\n00000\n01110\n01011\n01001\n00101\n01110"))
(defn col [i] (map (fn [s] (- (int (nth s i)) 48)) nums))
(def W (count (first nums)))
(defn gamma [] (Integer/parseInt (apply str (for [i (range W)] (if (> (apply + (col i)) (/ (count nums) 2)) 1 0))) 2))
(let [g (gamma) e (bit-xor g (dec (bit-shift-left 1 W)))]
  (println "power" (* g e)) (assert (= 184 (* g e))))
(println "2021d03 OK")
