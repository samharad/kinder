(ns kinder.dev
  (:require [kinder.state :as st]
            [kinder.draw :as draw]
            [clojure.spec.alpha :as spec]
            [orchestra.spec.test :as spect]
            [quil.core :as q]))

(comment "TODO:
  - Need to fix weird pixel issue.
")

(def golden-ratio 1.61803398875)
(def target-dim-inches [12 (* golden-ratio 12)])
(def target-ppi 300)

(def dim [2 2])
(def unit 300)
(def stroke-weight 3/10)
(def canvas-dims (mapv #(+ (* % unit) (* stroke-weight unit))
                       dim))

(defonce state (atom (st/init-state dim)))

(defn draw [] (draw/draw state
                         :unit unit
                         :stroke-weight stroke-weight))


(defn sketch []
  (q/sketch
    :title "Kinder"
    :setup #'kinder.draw/setup
    :settings (constantly nil)
    :draw #'kinder.dev/draw
    :features [:keep-on-top]
    :size canvas-dims))

(defonce kinder (sketch))

(defn refresh []
  (.loop kinder))

(do
  (st/complete! state)
  (refresh))

(comment
  (do
    (st/step-back! state)
    (refresh))

  (do
    (st/step! state)
    (refresh))

  (def kinder (sketch))
  (def state (atom (st/init-state dim))))

(spect/instrument)
(spec/check-asserts true)
(spec/assert :kinder.state/state @state)
