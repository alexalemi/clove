(def changes [6 -4 3 -8 4])
(defn first-twice [changes]
  (reduce (fn [[seen sum] d] (let [s (+ sum d)] (if (seen s) (reduced s) [(conj seen s) s])))
          [#{0} 0] (cycle changes)))
(println "twice" (first-twice changes))
(assert (= 6 (first-twice changes)))
(println "2018d01 OK")
