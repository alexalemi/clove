;; AoC 2021 d4 (bingo) — winning board + draws up to the win
(def draws [16 2 24 20 3 19 22 9 11 4 7 14])
(def board [[14 12 2 23 17][5 18 19 24 21][22 8 4 6 7][16 1 20 3 10][25 15 9 11 13]])
(defn wins? [marked board] (or (some (fn [row] (every? marked row)) board)
                               (some (fn [c] (every? marked (map (fn [row] (nth row c)) board))) (range 5))))
(defn play [board]
  (loop [drawn [] remaining draws]
    (let [marked (set drawn)]
      (if (wins? marked board)
        (* (last drawn) (apply + (for [row board n row :when (not (marked n))] n)))
        (recur (conj drawn (first remaining)) (rest remaining))))))
(println "score" (play board))
(assert (= 780 (play board)))
(println "2021d04 OK")
