(ns kinder.core
  "Mostly-pure functions that output and manipulate data representing
  a generative artwork image.

  Ignorant of Quil and state.
  "
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:require [random-seed.core :refer [rand rand-int rand-nth set-random-seed!]]
            [clojure.spec.alpha :as s]
            [orchestra.spec.test :as st])
  (:import (java.util Random)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Spec
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::color (s/coll-of pos-int? :kind vector? :count 3))
(s/def ::main ::color)
(s/def ::accent (s/coll-of ::color))
(s/def ::palette (s/keys :req-un [::main ::accent]))

(s/def ::seed integer?)

;; TODO: fails if "pos-int?" !!!!!
(s/def ::dim (s/coll-of int? :kind vector? :count 2))
(s/def ::loc (s/coll-of number? :kind vector? :count 2))
(s/def ::assigned-color ::color)
(s/def ::children (s/coll-of ::rect))
(s/def ::rect (s/keys :req-un [::dim ::loc ::color ::assigned-color]
                      :opt-un [::children]))

(s/def ::rad pos-int?)
(s/def ::circle (s/keys :req-un [::loc ::rad ::color]))
(s/def ::circles (s/coll-of ::circle))

(s/def ::pane (s/keys :req-un [::rect ::seed ::dim ::circles]))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def kinder-palette {:main [57, 8, 93]
                     :accent [[354, 99, 64]
                              [121, 41, 57]
                              [215, 99, 45]
                              [35, 77, 91]]})
                              ;[0, 100, 0]]})

(def red-palette {:main [57, 8, 93]
                  :accent [[354, 99, 64]]})

(def palettes [kinder-palette #_red-palette])

(def ^:dynamic palette kinder-palette)
(def ^:dynamic seed-rect {:dim [0 0]})

(defn- main-color []
  (:main palette))

(defn- some-accent-color []
  (rand-nth (:accent palette)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coloring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- assign-some-color [rect parent-color]
  (let [{:keys [dim]} rect
        [w h] dim
        color (cond
                (or (> w 3) (> h 3))
                (weighted-selection [[parent-color 1]
                                     [(some-accent-color) 10]])
                :else
                (weighted-selection [[parent-color 10]
                                     [(some-accent-color) 1]]))]
    (assoc rect :assigned-color color)))

(defn- express-some-color [rect]
  (let [{:keys [dim id assigned-color]} rect
        [w h] dim
        is-large (or (> w 3) (> h 3))
        accent assigned-color
        main (main-color)
        is-square (= w h)
        [id-a id-b] (concat
                      (->> id
                           (take-last 2)
                           (map str)
                           (map #(Integer/parseInt %)))
                      [0 0])
        id-indicates-color (= (mod id-a 2) (mod id-b 2))
        color (cond
                (not is-square) main
                is-large (weighted-selection [[main 10]
                                              [accent 3]])
                id-indicates-color (weighted-selection [[accent 10]
                                                        [main 1]])
                :else (weighted-selection [[main 10]
                                           [accent 1]]))]
    (assoc rect :color color)))

(defn- assign-and-express-color [rect parent-color]
  (express-some-color (assign-some-color rect parent-color)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rect child-bearers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- flip-axes [child-bearer-f]
  (let [reversev (comp vec reverse)
        flip-axes (fn [rect] (-> rect
                                 (update :dim reversev)
                                 (update :loc reversev)))]
    (fn [rect]
      (let [children (-> rect flip-axes child-bearer-f)]
        (doall (mapv flip-axes children))))))

(defn- horz-even-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        divs (divisors h)
        div (weighted-selection (mapv #(vector %1 %2)
                                      divs
                                      (range (count divs) 0 -1)))]
    (->> (range y (+ y h) div)
         (map-indexed #(assign-and-express-color {:dim [w div]
                                                  :loc    [x %2]
                                                  :id (str id %1)}
                                                 assigned-color))
         (doall))))
(def vert-even-children (flip-axes horz-even-children))

(defn- horz-sym-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        h' (inc (rand-int (int (/ h 3))))
        y'a y
        y'b (- (+ y h) h')
        r {:dim [w h']}]
    (->> [(assoc r :loc [x y'a])
          {:loc [x (+ y'a h')] :dim [w (- h h' h')]}
          (assoc r :loc [x y'b])]
         (map-indexed #(assoc %2 :id (str id %1)))
         (mapv #(assign-and-express-color % assigned-color))
         (doall))))

(def vert-sym-children (flip-axes horz-sym-children))

(defn- horz-rand-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        num-kids (+ 2 (rand-int 4))
        ranges (->> (repeatedly #(rand-nth (map inc (range y (+ y h)))))
                    (take num-kids)
                    (doall)
                    (sort)
                    (cons y)
                    (vec))
        ranges (->> (conj ranges (+ y h))
                    (partition 2 1)
                    (map vec))
        rs (doall
             (map-indexed (fn [i [ya yb]]
                            (assign-and-express-color {:dim [w (- yb ya)]
                                                       :loc    [x ya]
                                                       :id (str id i)}
                                                      assigned-color))
                          ranges))]
    rs))
(def vert-rand-children (flip-axes horz-rand-children))

(defn- children [sym rand even]
  (fn [rect]
    (let [{:keys [dim]} rect
          [w h] dim
          seed-dim (:dim seed-rect)
          [seed-w seed-h] seed-dim
          is-pretty-small (and true
                               ;(<= w 4) (<= h 4)
                               (< w (* 0.15 seed-w))
                               (< h (* 0.15 seed-h)))
          is-pretty-big (and (> w (* 0.5 seed-w))
                             (> h (* 0.5 seed-h)))
          is-very-big (and (> w (* 0.9 seed-w))
                           (> h (* 0.9 seed-h)))

          is-seed-rect (and (= w seed-w)
                            (= h seed-h))

          is-short-and-skinny (or
                                (and (<= w 3) (<= h 10))
                                (and (<= h 3) (<= w 10)))
          skinny 3 ;; TODO tweak me
          is-skinny (or (<= w skinny) (<= h skinny))
          is-maximally-skinny (or (= w 1) (= h 1))

          is-long-and-maximally-skinny (and is-maximally-skinny
                                            (or (> w 10) (> h 10)))
          ;is-short (or (<= w 10) (<= h 10))

          f (cond
              is-seed-rect
              (weighted-selection [[sym 1]])

              is-very-big
              (weighted-selection [[sym 6]
                                   [rand 3]])

              is-pretty-big
              (weighted-selection [[sym 6]
                                   [rand 3]
                                   [(constantly []) 4]])

              is-long-and-maximally-skinny
              (weighted-selection [[even 10]
                                   [rand 10]
                                   [(constantly []) 10]])

              is-maximally-skinny
              (weighted-selection [[even 10]
                                   ;[even 10]
                                   ;[rand 10]
                                   [(constantly []) 4]])

              ;is-short-and-skinny
              is-skinny ;; TODO tweak me
              (weighted-selection [[even 10]
                                   ;[even 10]
                                   ;[rand 10]
                                   [(constantly []) 6]])

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
      children)))

(def horz-children (children horz-sym-children
                             horz-rand-children
                             horz-even-children))

(def vert-children (children vert-sym-children
                             vert-rand-children
                             vert-even-children))

(defn- make-direct-children [rect]
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

(defn- with-some-direct-children [rect]
  (let [children (make-direct-children rect)]
    (assoc rect :children children)))


(defn take-rect-depth [depth rect]
  (if (zero? depth)
    (if (:children rect)
      (assoc rect :children [])
      rect)
    (if (:children rect)
      (-> rect
          (update :children (partial map #(take-rect-depth (dec depth) %)))
          (update :children doall))
      rect)))

(defn take-depth [depth pane]
  (let [{:keys [rect circles]} pane
        rect' (take-rect-depth depth rect)
        circles' (if (= rect (take-rect-depth depth rect))
                   circles
                   [])]
    (-> pane
        (assoc :rect rect')
        (assoc :circles circles'))))

(defn with-random-children [rect]
  (-> rect
      (with-some-direct-children)
      (update :children (partial map #(with-random-children %)))
      (update :children doall)))

(defn some-rect [dimensions]
  (let [accent (some-accent-color)
        main (main-color)]
    (-> (with-random-children {:dim            dimensions
                               :loc            [0 0]
                               :assigned-color accent
                               :color          main
                               :id             ""
                               :children       []})
        (update :children doall))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- own-corner-coords [rect]
  (let [{:keys [loc dim]} rect
        [w h] dim
        [x y] loc
        corners [[x y]
                 [x (+ y h)]
                 [(+ x w) y]
                 [(+ x w) (+ y h)]]]
    (set corners)))

(defn- all-corner-coords [rect]
  (let [corners (own-corner-coords rect)]
    (reduce into corners (doall (map all-corner-coords (:children rect))))))

(defn dist [[ax ay] [bx by]]
  (Math/sqrt (+ (Math/pow (- bx ax) 2)
                (Math/pow (- by ay) 2))))

(defn some-circle [rect circles]
  (let [rad (rand-nth (range 3 6))
        [w h] (:dim rect)
        all-corners (all-corner-coords rect)
        candidate-spots (map (fn [[x y]]
                               (let [jit #(rand-nth (range -1 2 0.1))]
                                 [(+ x (jit)) (+ y (jit))]))
                             all-corners)
        candidate-spots (->> candidate-spots
                             (filter (fn [[x y]]
                                       (and (< rad x (- w rad))
                                            (< rad y (- h rad)))))
                             (filter (fn [[x y]]
                                       (not (some (fn [c]
                                                    (>= (+ rad (:rad c))
                                                        (dist [x y] (:loc c))))
                                                  circles)))))]
    (if (empty? candidate-spots)
      []
      [{:rad rad :loc (rand-nth candidate-spots) :color (some-accent-color)}])))


(defn some-circles [rect]
  ;; TODO! Need to be:
  ;;  - Sometimes plain colored
  ;;  - Possibly: in a general cluster
  ;;  - Not on 'phantom' corners (bug)
  ;;  - Jitter-biased to the corner itself?
  (let [num-circles (rand-nth (range 3 5))]
    (loop [circles []
           calls 0]
      (if (= calls num-circles)
        circles
        (recur (into circles
                     (some-circle rect circles))
               (inc calls))))))

(comment
  "What should the interface of this module be?

  Currently it is:
  - seed-rect
  - with-random-children
  - some-circles
  - take-depth

  Should it be:

  (defn generate-artwork []
    ,,,)

  Here, the client would have no ability to dictate a color palette,
  or a set of color palettes to draw from, or a seed, or the dimensions
  of the piece. Hmmm.

  If this module is an artist accepting commissions -- what parameters
  would this opinionated artist accept? Maybe:
  - Dimensions
  - Optionally: seed
  - Optionally: any configurable parameters, e.g. palette selection.
    Useful e.g. for generating a triptych that demonstrates something,
    e.g. all with the same palette, or with ascending entropy, etc.
  ")

(s/fdef generate-pane
  :args (s/cat :dimensions ::dim :opts (s/keys* :opt-un [::seed ::palette]))
  :ret ::pane)
(defn generate-pane [dimensions & {:keys [seed palette]}]
  "Outputs a kinder-symphony work. Demands dimensions in the form
   of [w h] -- we refuse to generate random dimensions.
   Other parameters are optional.
   "
  (let [seed (or seed (-> (Random.) .nextLong))
        _ (set-random-seed! seed)
        ;; Always select a palette, so that seed is deterministic
        default-palette (rand-nth palettes)
        palette (or palette default-palette)]
    (binding [palette palette
              seed-rect {:dim dimensions}]
      (let [rect (some-rect dimensions)
            circles (some-circles rect)]
        {:rect    rect
         :circles circles
         :seed    seed
         :dim     dimensions}))))

(st/instrument)
