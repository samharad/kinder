(ns kinder.dev
  (:require [kinder.state :as st]
            [kinder.draw :as draw]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as spec]
            [orchestra.spec.test :as spect]
            [quil.core :as q]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)))

(defn- get-short-sha []
  (-> (sh "git" "rev-parse" "--short" "HEAD") :out str/trim))

(defn- timestamp []
  (let [now (LocalDateTime/now)
        ms  (quot (.getNano now) 1000000)]
    (format "%d-%02d-%02d-%02d%02d%02d%03d"
            (.getYear now) (.getMonthValue now) (.getDayOfMonth now)
            (.getHour now) (.getMinute now) (.getSecond now) ms)))

(def dim [30 70])
(def unit 10)
(def stroke-weight 2/10)
(def canvas-dims (mapv #(+ (* % unit ) (* stroke-weight unit))
                       dim))

(def state (atom (st/init-state dim)))
(st/complete! state)

(defn get-filename []
  (str (timestamp) "-" (:seed @state) "-" (get-short-sha) ".svg"))

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
