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

(comment
  "TODO:
    - Only color small, square boxes
    - Checker-colors. I think this requires either: coloring a box
      with respect to its parent, or just doing the coloring as a
      second pass.
    - A parent box can be 'red'. And then, when I give it covering
      even-spaced children, those can take be colored in alternating
      (parent-color, plain, parent-color, plain...). But the problem
      is, we alternate stripes, and then when we go to color a white
      stripe, which color do we choose... you kind of want the grand-
      parent color. Hmmmmm.
      Or, each box has an indexed-based ID.
      Root-box: ''
      Children: ['0', '1', '2']
      Children of that: [['00', '01', '02'],
                         ['10', '11', '12'],
                         ['20', '21', '22']]
      Boxes inherit the color-assignment of their parent, but only
      express it based on their ID, particularly its last two digits
      (if they are both even or both odd), combined with some jitter.
      Assigned color is always an accent.
      Assigned color might 'mutate' in a child, randomly. Much more
      likely to occur when the parent is a large box. Also depends
      on the child-gen method: mutation is more likely for random-gen,
      less likely for even-gen.

    - Refactor.

      seed-rect :: Rect
      give-children :: Rect -> Rect
      with-children :: Rect -> [Rect] -> Rect
      with-color :: Rect -> Color -> Rect

  ")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def seed (-> (Random.) .nextLong))
;(set-random-seed! -9120299532266499569)
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
        color (if is-large
                (weighted-selection [[accent 1]
                                     [main 10]])
                (weighted-selection [[accent 1]
                                     [main 3]]))]
    (assoc rect :color color)))

(def seed-rect {:dim [30 60]
                :loc [0 0]
                :color (:main palette)
                :id ""
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
        div (weighted-selection (mapv #(vector %1 %2)
                                      divs
                                      (range (count divs) 0 -1)))]
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
                           {:loc [x (+ y'a h')] :dim [w (- h h' h')]}
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
                    (vec))
        ranges (->> (conj ranges (+ y h))
                    (partition 2 1)
                    (map vec))
        rs (map (fn [[ya yb]]
                  (with-some-color {:dim [w (- yb ya)]
                                    :loc [x ya]}))
                ranges)]
    rs))
(def vert-rand-children (flip-axes horz-rand-children))

(defn children [sym rand even]
  (fn [rect]
    (let [{:keys [dim]} rect
          [w h] dim
          seed-dim (:dim seed-rect)
          [seed-w seed-h] seed-dim
          is-pretty-small (and (< w (* 0.15 seed-w))
                               (< h (* 0.15 seed-h)))
          is-pretty-big (and (> w (* 0.5 seed-w))
                             (> h (* 0.5 seed-h)))
          is-very-big (and (> w (* 0.9 seed-w))
                           (> h (* 0.9 seed-h)))

          f (cond
              is-very-big
              (weighted-selection [[sym 6]
                                   [rand 3]])

              is-pretty-big
              (weighted-selection [[sym 6]
                                   [rand 3]
                                   [(constantly []) 2]])

              is-pretty-small
              (weighted-selection [[even 2]
                                   [rand 1]
                                   [sym 1]
                                   [(constantly []) 10]])

              :else
              (weighted-selection [[sym 4]
                                   [rand 8]
                                   [even 2]
                                   [(constantly []) 10]]))
            children (f rect)]
      (map-indexed (fn [i child]
                     (assoc child :id (str (:id rect) i)))
                   children))))

(def horz-children (children horz-sym-children
                             horz-rand-children
                             horz-even-children))

(def vert-children (children vert-sym-children
                             vert-rand-children
                             vert-even-children))

(defn make-direct-children [rect]
  (let [[w h] (:dim rect)
        is-vert (> h w)]
    (cond
      (and (<= w 1) (<= h 1)) []
      (<= w 1) (horz-children rect)
      (<= h 1) (vert-children rect)
      is-vert (weighted-selection [[(horz-children rect) 10]
                                   [(vert-children rect) 1]])
      :else (weighted-selection [[(horz-children rect) 1]
                                 [(vert-children rect) 10]]))))

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
        (let [{:keys [dim loc children color id]} rect
              [x y] loc
              [w h] dim]
          ;; Set child-bearing box color to neon pink so that
          ;; we can catch impartial tiling!
          (if (not-empty children)
            (q/fill 315 100 100)
            (q/fill color))
          (q/rect (unit x) (unit y) (unit w) (unit h))
          #_(when (not (seq children))
              (q/fill 360 0 0)
              (q/text id (+ (unit x) (/ (unit w) 2))
                         (+ (unit y) (/ (unit h) 2))))
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