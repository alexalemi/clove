(require '[clojure.data.priority-map :refer [priority-map]])
(def raw "3296338373\n6613113736\n5971594343\n5546148273\n3871429147\n1319216943\n2664128787\n3852634336\n9383751114\n1697889536")
(def grid (mapv (fn [l] (mapv (fn [c] (- (int c) 48)) l)) (clojure.string/split-lines raw)))
(def H (count grid)) (def W (count (first grid)))
(defn nbrs [[r c]] (filter (fn [[r c]] (and (>= r 0) (< r H) (>= c 0) (< c W))) [[(dec r) c] [(inc r) c] [r (dec c)] [r (inc c)]]))
(defn dijkstra []
  (loop [pq (priority-map [0 0] 0) dist {}]
    (if (empty? pq) (dist [(dec H) (dec W)])
      (let [[node d] (peek pq) pq (pop pq)]
        (if (contains? dist node) (recur pq dist)
          (recur (reduce (fn [q n] (let [nd (+ d (get-in grid n))] (assoc q n (min nd (get q n nd))))) pq (remove dist (nbrs node)))
                 (assoc dist node d)))))))
(println "risk" (dijkstra))
(assert (= 47 (dijkstra)))
(println "2021d15 OK")
