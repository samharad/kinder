(ns kinder.dev
  (:require [kinder.state :as st]
            [kinder.draw :as draw]
            [clojure.spec.alpha :as spec]
            [orchestra.spec.test :as spect]
            [quil.core :as q]))

(defonce state (atom (st/init-state [30 60])))

(def draw (partial draw/draw state))

(defonce kinder (q/sketch
                  :title "Kinder"
                  :setup (constantly nil)
                  :settings (constantly nil)
                  :draw kinder.dev/draw
                  :features [:keep-on-top :resizable]
                  :size [400 700]))

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
    (refresh)))

(spect/instrument)
(spec/check-asserts true)
(spec/assert :kinder.state/state @state)
