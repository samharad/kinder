(ns kinder.dev
  (:require [kinder.state :as st]
            [kinder.draw :as draw]
            [clojure.spec.alpha :as spec]
            [orchestra.spec.test :as spect]
            [quil.core :as q]))

(def dim [30 60])
(def unit 10)

(defonce state (atom (st/init-state dim)))

(defn draw [] (draw/draw state :unit unit))

(defn sketch []
  (q/sketch
    :title "Kinder"
    :setup #'kinder.draw/setup
    :settings (constantly nil)
    :draw #'kinder.dev/draw
    :features [:keep-on-top]
    :size [(* unit 40) (* unit 70)]))

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

  (def kinder (sketch)))

(spect/instrument)
(spec/check-asserts true)
(spec/assert :kinder.state/state @state)
