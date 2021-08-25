(ns kinder.core
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:require [quil.core :as q]
            [clojure.walk :as walk]
            [random-seed.core :refer [rand rand-int rand-nth set-random-seed!]])
  (:import (java.time LocalDateTime)
           (java.util Random)))

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

(defn horz-sym-children [rect]
  (let [{:keys [dim loc]} rect
        [w h] dim
        [x y] loc
        h' (inc (rand-int (int (/ h 3))))
        y'a y
        y'b (- (+ y h) h')
        r (with-some-color {:dim [w h']})]
    [(assoc r :loc [x y'a])
     (assoc r :loc [x y'b])]))

(defn horz-children [rect]
  (rand-nth [;; TODO: only horz sym children if room to chop into 3rds...
             (horz-sym-children rect)]))


(defn vert-sym-children [rect]
  (let [{:keys [dim loc]} rect
        [w h] dim
        [x y] loc
        w' (inc (rand-int (int (/ w 3))))
        x'a x
        x'b (- (+ x w) w')
        r (with-some-color {:dim [w' h]})]
    [(assoc r :loc [x'a y])
     (assoc r :loc [x'b y])]))

(defn vert-children [rect]
  (rand-nth [(vert-sym-children rect)]))

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
  (q/save (str "output/wip/"
               (.toString (LocalDateTime/now))
               "_"
               seed
               ".tif")))

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