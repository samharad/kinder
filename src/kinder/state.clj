(ns kinder.state
  (:require [kinder.core :as core]
            [quil.core :as q]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [orchestra.spec.test :as st]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)))

(comment
  "TODO:
    - Figure out high-res output
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

(s/def ::render-depth (s/and int? (complement neg?)))
(s/def ::state (s/keys :req-un [::core/pane ::render-depth]))

(defn init-state []
  (let [pane (core/generate-pane [30 60])]
    (-> {:pane pane}
        (assoc :render-depth 0))))

(defn step-state [state]
  (let [{:keys [render-depth pane]} state]
    (if (= pane (core/take-depth render-depth pane))
      state
      (update state :render-depth inc))))

(defn back-state [state]
  (let [{:keys [render-depth]} state]
    (if (= render-depth 0)
      state
      (update state :render-depth dec))))


(defonce state (atom (init-state)))

(defn reset []
  (reset! state (init-state)))

(defn all-done []
  (= (:pane @state)
     (core/take-depth (:render-depth @state) (:pane @state))))

(defn step []
  (if (all-done)
    (reset)
    (swap! state step-state)))

(defn run []
  (reset)
  (while (not (all-done))
   (step)))

(run)
(declare refresh)
(comment
  (do
    (swap! state back-state)
    (refresh)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Quil
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unit
  ([] 10.0)
  ([v] (* (unit) v)))

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
                        (:rect (core/take-depth (:render-depth @state)
                                                (:pane @state))))
                      (doseq [circle (:circles (core/take-depth (:render-depth @state)
                                                                (:pane @state)))]
                        (let [{:keys [loc rad color]} circle
                              [x y] loc]
                          (q/fill color)
                          (q/ellipse (unit x) (unit y) (unit rad) (unit rad)))))
  ;; STATS section
  (q/with-translation [20 650]
    (when (all-done)
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

(st/instrument)
(s/check-asserts true)
(s/assert ::state @state)
(refresh)