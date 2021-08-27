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
    - 2x2s, 3x3s should be more likely to mutate color
    - Make the 'rand' child-gen less random, per source
    - Find a happy stroke weight (currently needs to be an odd #)
    - Mark boxes as 'DONE'; make them bright pink until they're done;
      will help with step-through comprehensibility.
    - Tweak parameters
    - Circles
    - Refactor
    - I know why the checkering is inconsistent. It's because a box
      might first divide evenly into stripes, then each of those may
      divide randomly, then checker.

   CHECKER-COLORS EXPLANATION:
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
  ")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def kinder-palette {:main [57, 8, 93]
                     :accent [[354, 99, 64]
                              [121, 41, 57]
                              [215, 99, 45]
                              [35, 77, 91]]})
                              ;[0, 100, 0]]})

(def palette kinder-palette)

(defn main-color []
  (:main palette))

(defn some-accent-color []
  (rand-nth (:accent palette)))

(def seed-rect {:dim [30 60]
                :loc [0 0]
                ;:assigned-color (some-accent-color)
                :assigned-color (first (:accent palette))
                :color (main-color)
                :id ""
                :children []})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coloring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assign-some-color [rect parent-color]
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

(defn express-some-color [rect]
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

(defn assign-and-express-color [rect parent-color]
  (express-some-color (assign-some-color rect parent-color)))


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
                                                 assigned-color)))))
(def vert-even-children (flip-axes horz-even-children))

(defn horz-sym-children [rect]
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
         (mapv #(assign-and-express-color % assigned-color)))))

(def vert-sym-children (flip-axes horz-sym-children))

(defn horz-rand-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
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
        rs (map-indexed (fn [i [ya yb]]
                          (assign-and-express-color {:dim [w (- yb ya)]
                                                     :loc    [x ya]
                                                     :id (str id i)}
                                                    assigned-color))
                        ranges)]
    rs))
(def vert-rand-children (flip-axes horz-rand-children))

(defn children [sym rand even]
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

              is-maximally-skinny
              (weighted-selection [[even 10]
                                   ;[even 10]
                                   ;[rand 10]
                                   [(constantly []) 2]])

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

(defn with-some-direct-children [rect]
  (let [children (make-direct-children rect)]
    (assoc rect :children children)))

(defn take-depth [depth rect]
  (if (zero? depth)
    (if (:children rect)
      (assoc rect :children [])
      rect)
    (if (:children rect)
      (update rect :children (partial map #(take-depth (dec depth) %)))
      rect)))

(defn with-random-children
  ([rect]
   (-> rect
       (with-some-direct-children)
       (update :children (partial map #(with-random-children %)))))
  #_([rect depth-to-realize]
     (if (= 0 depth-to-realize)
       rect
       (-> rect
           (with-some-direct-children)
           (update :children (partial map #(with-random-children % (dec depth-to-realize))))))))

#_(defn realize [state]
    (let [{:keys [depth-to-realize root-rect]} state]
      (assoc state :root-rect (with-random-children root-rect depth-to-realize))))

(defn init-state []
  (let [seed (-> (Random.) .nextLong)
        _ (set-random-seed! seed)
        root-rect (with-random-children seed-rect)]
    {:seed seed
     :depth-to-realize 0
     :root-rect root-rect
     :render-rect seed-rect}))

(defn reset-state [state]
  (init-state))

(defonce state (atom (init-state)))

(defn step-state [state]
  (let [{:keys [depth-to-realize root-rect is-complete]} state]
    (if is-complete
      state
      (let [depth-to-realize' (inc depth-to-realize)
            render-rect' (take-depth depth-to-realize' root-rect)
            is-complete (= render-rect' (take-depth (inc depth-to-realize') root-rect))]
        (-> state
            (assoc :depth-to-realize depth-to-realize')
            (assoc :render-rect render-rect')
            (assoc :is-complete is-complete))))))

(defn back-state [state]
  (let [{:keys [depth-to-realize root-rect render-rect]} state]
    (if (= render-rect seed-rect)
      (do
        (prn "here")
        state)
      (let [depth-to-realize' (dec depth-to-realize)
            render-rect' (take-depth depth-to-realize' root-rect)]
        (prn "here")
        (-> state
            (assoc :depth-to-realize depth-to-realize')
            (assoc :render-rect render-rect')
            (assoc :is-complete false))))))


(defn reset []
  (swap! state reset-state))

(defn step []
  (if (:is-complete @state)
    (swap! state reset-state)
    (swap! state step-state)))

(defn run []
  (reset)
  (while (not (:is-complete @state))
   (step)))

(run)

(declare refresh)
(comment
  (do
    (swap! state back-state)
    (refresh))






  (defn unit
    ([] 10.0)
    ([v] (* (unit) v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Quil
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn setup-kinder [])
(defn draw-kinder []
  (q/no-loop)
  (q/color-mode :hsb 360 100 100 1.0)
  (q/background 360 0 100)
  (q/stroke-weight 3)
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
                                (q/text-size 8)
                                (q/text id (+ (unit x))
                                           (+ (unit y) (/ (unit h) 2))))
                            children))
                        (:render-rect @state)))
  ;; STATS section
  (q/with-translation [20 650]
    (when (:is-complete @state)
      (q/fill 0)
      (q/text-size 30)
      (q/text "DONE!" 0 0)))
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
               :setup kinder.core/setup-kinder
               :draw kinder.core/draw-kinder
               :features [:keep-on-top :resizable]
               :size [400 700]))

(defn refresh []
  (.loop kinder))

(refresh)
