(ns kinder.util)

(defn divisors [n]
  (->> (range 1 (inc n))
       (filter #(zero? (rem n %)))))

(defn weighted-selection [pairs]
  (let [total (reduce + (map second pairs))
        r (rand-int total)]
    (reduce (fn [acc [v n]]
              (let [acc' (+ acc n)]
                (if (< r acc')
                  (reduced v)
                  acc')))
            0
            pairs)))
