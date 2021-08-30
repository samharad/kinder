(ns kinder.state
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:require [kinder.core :as core]
            [quil.core :as q]
            [clojure.walk :as walk]
            [random-seed.core :refer [rand rand-int rand-nth set-random-seed!]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)
           (java.util Random)))

(comment
  "TODO:
    - Make it smooth and good-looking
    - Decouple is-complete from circles
    - 2x2s, 3x3s should be more likely to mutate color
    - Make larger accent squares just slightly more common
    - Make the 'rand' child-gen less random, per source
    - Find a happy stroke weight (currently needs to be an odd #)
    - Mark boxes as 'DONE'; make them bright pink until they're done;
      will help with step-through comprehensibility.
    - Tweak parameters
    - Refactor
    - I know why the checkering is inconsistent. It's because a box
      might first divide evenly into stripes, then each of those may
      divide randomly, then checker.
    - Utils for marking the save-file for particularly good ones
    - More palettes

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

(defn init-state []
  (let [seed (-> (Random.) .nextLong)
        _ (set-random-seed! seed)
        root-rect (core/with-random-children core/seed-rect)
        circles (core/some-circles root-rect)]
    {:seed seed
     :depth-to-realize 0
     :root-rect root-rect
     :render-rect core/seed-rect
     :circles circles}))

(defn reset-state [state]
  (init-state))

(defonce state (atom (init-state)))

(defn step-state [state]
  (let [{:keys [depth-to-realize root-rect is-complete]} state]
    (if is-complete
      state
      (let [depth-to-realize' (inc depth-to-realize)
            render-rect' (core/take-depth depth-to-realize' root-rect)
            is-complete (= render-rect' (core/take-depth (inc depth-to-realize') root-rect))]
        (-> state
            (assoc :depth-to-realize depth-to-realize')
            (assoc :render-rect render-rect')
            (assoc :is-complete is-complete))))))

(defn back-state [state]
  (let [{:keys [depth-to-realize root-rect render-rect]} state]
    (if (= render-rect core/seed-rect)
      (do
        (prn "here")
        state)
      (let [depth-to-realize' (dec depth-to-realize)
            render-rect' (core/take-depth depth-to-realize' root-rect)]
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
    (refresh)))

(defn unit
  ([] 10.0)
  ([v] (* (unit) v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Quil
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn settings-kinder []
  (q/smooth))
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
                        (:render-rect @state))
                      (when (:is-complete @state)
                        (doseq [circle (:circles @state)]
                          (let [{:keys [loc rad color]} circle
                                [x y] loc]
                            (q/fill color)
                            (q/ellipse (unit x) (unit y) (unit rad) (unit rad))))))
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
                 (:seed @state)
                 "_"
                 commit-msg
                 ".tif"))))


(comment
  (q/defsketch kinder
               :title "Kinder"
               :setup kinder.state/setup-kinder
               :settings kinder.state/settings-kinder
               :draw kinder.state/draw-kinder
               :features [:keep-on-top :resizable]
               :size [400 700]))

(defn refresh []
  (.loop kinder))

(refresh)