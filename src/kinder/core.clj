(ns kinder.core
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:require [quil.core :as q]
            [clojure.walk :as walk]
            [random-seed.core :refer [rand rand-int rand-nth set-random-seed!]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)
           (java.util Random)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn divisors [n]
  (->> (range 1 (inc n))
       (filter #(zero? (rem n %)))))

(comment
  "TODO:
    - Bias horz/vert selection based on dimensions
    - Bias child-gen function by size/dimension
  ")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def seed (-> (Random.) .nextLong))
(set-random-seed! seed)

(def kinder-palette {:main [57, 8, 93]
                     :accent #{[354, 99, 64]
                               [121, 41, 57]
                               [215, 99, 45]
                               [35, 77, 91]}})

(def palette (rand-nth [kinder-palette]))

(defn with-some-color [rect]
  (let [{:keys [dim]} rect
        [w h] dim
        is-large (or (> w 1) (> h 1))
        accent (rand-nth (seq (:accent palette)))
        main (:main palette)
        rand (rand-int 10)
        color (if is-large
                (if (= rand 1)
                  accent
                  main)
                (if (> rand 3)
                  accent
                  main))]
    (assoc rect :color color)))

(def seed-rect {:dim [30 60]
                :loc [0 0]
                :color (:main palette)
                :children []})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Child-bearers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn flip-axes [child-bearer-f]
  (let [reversev (comp vec reverse)
        flip-axes (fn [rect] (-> rect
                                 (update :dim reversev)
                                 (update :loc reversev)))]
    (fn [rect]
      (let [children (-> rect flip-axes child-bearer-f)]
        (mapv flip-axes children)))))

(defn horz-even-children [rect]
  (let [{:keys [dim loc]} rect
        [w h] dim
        [x y] loc
        divs (divisors h)
        div (rand-nth divs)] ;; TODO bias this to 1
    (->> (range y (+ y h) div)
         ;; TODO fix coloring
         (map #(with-some-color {:dim [w div]
                                 :loc [x %]})))))
(def vert-even-children (flip-axes horz-even-children))

(defn horz-sym-children [rect]
  (let [{:keys [dim loc]} rect
        [w h] dim
        [x y] loc
        h' (inc (rand-int (int (/ h 3))))
        y'a y
        y'b (- (+ y h) h')
        r {:dim [w h']}]
    (mapv with-some-color [(assoc r :loc [x y'a])
                           (assoc r :loc [x y'b])])))
(def vert-sym-children (flip-axes horz-sym-children))

(defn horz-rand-children [rect]
  (let [{:keys [dim loc]} rect
        [w h] dim
        [x y] loc
        num-kids (+ 2 (rand-int 4))
        ranges (->> (repeatedly #(rand-nth (map inc (range y (+ y h)))))
                    (take num-kids)
                    (sort)
                    (cons y)
                    (partition 2 1)
                    (map vec))
        rs (map (fn [[ya yb]]
                  (with-some-color {:dim [w (- yb ya)]
                                    :loc [x ya]}))
                ranges)]
    rs))
(def vert-rand-children (flip-axes horz-rand-children))

(defn horz-children [rect]
  (rand-nth [
             (horz-rand-children rect);; TODO: only horz sym children if room to chop into 3rds...
             (horz-even-children rect)
             (horz-sym-children rect)
             []]))

(defn vert-children [rect]
  (rand-nth [(vert-sym-children rect)
             (vert-even-children rect)
             (vert-rand-children rect)
             []]))

(defn make-direct-children [rect]
  (let [[w h] (:dim rect)]
    (cond
      (and (<= w 1) (<= h 1)) []
      (<= w 1) (horz-children rect)
      (<= h 1) (vert-children rect)
      :else (rand-nth [(horz-children rect)
                       (vert-children rect)]))))

(defn with-random-children [rect]
  (let [children (make-direct-children rect)
        children (map with-random-children children)]
    (assoc rect :children children)))

(def root-rect (with-random-children seed-rect))

(defn unit
  ([] 10.0)
  ([v] (* (unit) v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Quil
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn setup [])
(defn draw []
  (q/no-loop)
  (q/color-mode :hsb 360 100 100 1.0)
  (q/background 360 0 100)
  (q/with-translation [20 20]
    (walk/prewalk
      (fn [rect]
        (let [{:keys [dim loc children color]} rect
              [x y] loc
              [w h] dim]
          (q/fill color)
          (q/rect (unit x) (unit y) (unit w) (unit h))
          children))
      root-rect))
  (let [commit-msg (-> (sh "git" "log" "-1" "--pretty=%s")
                       :out
                       (str/trim)
                       (str/replace " " "-"))]
    (q/save (str "output/wip/"
                 (.toString (LocalDateTime/now))
                 "_"
                 seed
                 "_"
                 commit-msg
                 ".tif"))))

(comment
  (q/defsketch kinder
    :title "Kinder"
    :setup kinder.core/setup
    :draw kinder.core/draw
    :features [:keep-on-top]
    :size [400 700]))

(defn refresh []
  (.loop kinder))

(refresh)