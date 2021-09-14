(ns kinder.dev
  (:require [kinder.state :as st]
            [kinder.draw :as draw]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as spec]
            [orchestra.spec.test :as spect]
            [quil.core :as q]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)))

(defn get-commit-message []
  (-> (sh "git" "log" "-1" "--pretty=%s")
      :out
      (str/trim)
      (str/replace " " "-")))

(def dim [30 70])
(def unit 10)
(def stroke-weight 2/10)
(def canvas-dims (mapv #(+ (* % unit ) (* stroke-weight unit))
                       dim))

(def state (atom (st/init-state dim)))
(st/complete! state)

(defn get-filename []
  (str (.toString (LocalDateTime/now))
       "_"
       (:seed @state)
       "_"
       (get-commit-message)
       ".svg"))

(def filename (str "output/svg/" (get-filename)))
(def dev-filename (str "output/svg/dev.svg"))

(defn draw []
  (draw/draw state
             :unit unit
             :stroke-weight stroke-weight))


(defn sketch []
  (q/sketch
    :title "Kinder"
    :renderer :svg
    :output-file dev-filename
    :setup #'kinder.draw/setup
    :settings #'kinder.draw/settings
    :draw #'kinder.dev/draw
    :size canvas-dims))

(spect/instrument)
(spec/check-asserts true)
(spec/assert :kinder.state/state @state)

(do
  (sketch)
  (Thread/sleep 1000)
  (prn (sh "cp" dev-filename filename)))
