(ns kinder.cli
  (:require [kinder.core :as core]
            [kinder.state :as st]
            [kinder.svg :as svg]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)
           (java.util Random)))

(def ^:private palette-map
  {"kinder" core/kinder-palette
   "red"    core/red-palette
   "orange" core/orange-palette})

(def ^:private layouts #{"single" "triptych" "triptych-equal"})

(def ^:private cli-options
  [["-W" "--width INT"    "Canvas width in units"          :default 30  :parse-fn #(Integer/parseInt %)]
   ["-H" "--height INT"   "Canvas height in units"         :default 70  :parse-fn #(Integer/parseInt %)]
   ["-u" "--unit INT"     "Pixels per unit"                :default 10  :parse-fn #(Integer/parseInt %)]
   ["-s" "--seed INT"     "Fix the random seed"            :parse-fn #(Long/parseLong %)]
   ["-p" "--palette NAME" "Palette: kinder | red | orange" :default "kinder"
    :validate [#(contains? palette-map %) "Must be kinder, red, or orange"]]
   ["-o" "--out PATH"     "Output SVG path (single piece only)"]
   ["-n" "--count INT"    "Number of pieces to generate"   :default 1   :parse-fn #(Integer/parseInt %)]
   ["-l" "--layout NAME"  "Layout: single | triptych | triptych-equal" :default "single"
    :validate [layouts "Must be single, triptych, or triptych-equal"]]
   ["-O" "--open"         "Open each SVG after generating"]
   ["-h" "--help"]])

(defn get-short-sha []
  (-> (sh "git" "rev-parse" "--short" "HEAD") :out str/trim))

(defn timestamp []
  (let [now (LocalDateTime/now)
        ms  (quot (.getNano now) 1000000)]
    (format "%d-%02d-%02d-%02d%02d%02d%03d"
            (.getYear now) (.getMonthValue now) (.getDayOfMonth now)
            (.getHour now) (.getMinute now) (.getSecond now) ms)))

(defn output-path [seed]
  (str "output/svg/" (timestamp) "-" seed "-" (get-short-sha) ".svg"))

(defn make-pane [dim seed palette]
  (let [state (atom {:pane (core/generate-pane dim :seed seed :palette palette)
                     :render-depth 0})]
    (st/complete! state)
    (:pane @state)))

(defn- finish! [out-file open?]
  (println out-file)
  (when open? (sh "open" out-file)))

(defn- generate-one [opts seed]
  (let [{:keys [width height unit palette out open]} opts
        stroke-weight 0.2
        pal      (get palette-map palette)
        seed     (or seed (-> (Random.) .nextLong))
        pane     (make-pane [width height] seed pal)
        out-file (or out (output-path seed))]
    (io/make-parents out-file)
    (spit out-file (svg/render pane unit stroke-weight))
    (finish! out-file open)))

(defn- generate-triptych [opts seed center-multiplier]
  (let [{:keys [width height unit palette out open]} opts
        stroke-weight 0.2
        gap      1
        pal      (get palette-map palette)
        seed     (or seed (-> (Random.) .nextLong))
        left     (make-pane [width height] seed pal)
        center   (make-pane [(* center-multiplier width) height] (+ seed 1) pal)
        right    (make-pane [width height] (+ seed 2) pal)
        out-file (or out (output-path seed))]
    (io/make-parents out-file)
    (spit out-file (svg/render-triptych left center right unit stroke-weight gap))
    (finish! out-file open)))

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (println summary)

      errors
      (do (doseq [e errors] (println e))
          (System/exit 1))

      :else
      (case (:layout options)
        "triptych"       (generate-triptych options (:seed options) 2)
        "triptych-equal" (generate-triptych options (:seed options) 1)
        "single"
        (let [{:keys [seed count]} options
              n (if (and seed (> count 1)) 1 count)]
          (when (and seed (> count 1))
            (println "Warning: --seed ignores --count; generating one piece"))
          (dotimes [i n]
            (generate-one options (when (zero? i) seed)))
          (System/exit 0))))))
