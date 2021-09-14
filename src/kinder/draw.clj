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

(defn settings []
  (q/smooth))

(defn setup [])

(defn draw [state & {:as opts}]
  (let [u (or (:unit opts) 10)
        stroke-weight (or (:stroke-weight opts) 3/10)
        unit (fn unit
               ([] u)
               ([v] (* v (unit))))
        stroke-weight (unit stroke-weight)
        {:keys [render-depth pane]} @state
        rect (:rect (core/take-depth render-depth pane))]
    (q/no-loop)
    (q/color-mode :hsb 360 100 100 1.0)
    (q/background 360 0 100)
    (q/stroke-weight stroke-weight)
    (q/with-translation [(/ stroke-weight 2) (/ stroke-weight 2)]
                        (walk/prewalk
                          (fn [rect]
                            (let [{:keys [dim loc children color radius]} rect
                                  [x y] loc
                                  [w h] dim]
                              ;; Set child-bearing box color to neon pink so that
                              ;; we can catch impartial tiling!
                              (if (not-empty children)
                                (q/fill 315 100 100)
                                (q/fill color))
                              (q/rect (unit x) (unit y) (unit w) (unit h) (or radius 2))
                              children))
                          rect)
                        (doseq [circle (:circles (core/take-depth (:render-depth @state)
                                                                  (:pane @state)))]
                          (let [{:keys [loc rad color]} circle
                                [x y] loc]
                            (q/fill color)
                            (q/ellipse (unit x) (unit y) (unit rad) (unit rad))))
                        (q/fill 0 0)
                        (let [{:keys [dim loc radius]} rect
                              [x y] loc
                              [w h] dim]
                          (q/rect (unit x) (unit y) (unit w) (unit h) (or radius 2))))
    #_(let [commit-msg (-> (sh "git" "log" "-1" "--pretty=%s")
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