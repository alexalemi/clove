(defn run [prog] (loop [m prog ip 0]
  (let [op (nth m ip)]
    (if (= op 99) m
      (let [a (nth m (nth m (+ ip 1))) b (nth m (nth m (+ ip 2))) d (nth m (+ ip 3)) f (if (= op 1) + *)]
        (recur (assoc m d (f a b)) (+ ip 4)))))))
(def prog [1 10 11 3 2 3 12 0 99 0 6 7 8])
(println "result" (first (run prog)))
(assert (= 104 (first (run prog))))
(println "2019d02 OK")
