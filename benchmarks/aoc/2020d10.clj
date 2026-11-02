(def adapters [3 1 4 7 8 10 11 14 2 5 13])
(defn solve [xs]
  (let [s (sort (conj (set xs) 0 (+ 3 (apply max xs))))
        diffs (map - (rest s) s)
        f (frequencies diffs)]
    [(* (get f 1 0) (get f 3 0))
     (let [mem (atom {})]
       (letfn [(ways [i] (if (= i (last s)) 1
                           (or (@mem i)
                               (let [r (apply + (for [n s :when (and (> n i) (<= (- n i) 3))] (ways n)))]
                                 (swap! mem assoc i r) r))))]
         (ways 0)))]))
(let [[p1 p2] (solve adapters)] (println "p1" p1 "p2" p2)
  (assert (= 8 p1)) (assert (= 225 p2)))
(println "2020d10 OK")
