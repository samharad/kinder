(ns kinder.draw
  "Draws Kinder panes."
  (:require [kinder.core :as core]
            [quil.core :as q]
            [clojure.walk :as walk]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)))

(comment
  "TODO:
    - Relate unit and sketch dimensions
    - Draw SHOULD NOT DEPEND ON STATE, but on a pane+render depth?
      Related: maybe render-depth should live within pane...
      And where should unit size be kept?
      Need to think about boundaries more.
  ")

(defn setup []
  (q/smooth))

(defn draw [state & {:as opts}]
  ;(q/scale 0.5)
  (let [u (or (:unit opts) 10)
        stroke-weight (or (:stroke-weight opts) 3/10)
        unit (fn unit
               ([] u)
               ([v] (* v (unit))))
        stroke-weight (unit stroke-weight)]
    (q/no-loop)
    (q/color-mode :hsb 360 100 100 1.0)
    (q/background 360 0 100)
    (q/stroke-weight stroke-weight)
    (q/with-translation [(/ stroke-weight 2) (/ stroke-weight 2)]
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
    #_(q/with-translation [20 650]
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
                   ".tif")))))
