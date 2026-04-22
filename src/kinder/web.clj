(ns kinder.web
  (:require [kinder.core :as core]
            [kinder.svg :as svg]
            [kinder.cli :as cli]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import (java.util Random)
           (java.net URLDecoder)))

(def ^:private palette-map
  {"kinder" core/kinder-palette
   "red"    core/red-palette
   "orange" core/orange-palette})

(def ^:private defaults
  {:width 30 :height 70 :unit 10 :stroke-weight 0.2 :palette "kinder" :gap 6
   ;; Subdivision density knobs (all modes) -- see kinder.core
   :empty-weight-scale 1.0 :divisor-bias 1.0 :cut-direction-bias 1.0
   :corner-radius 2.0
   ;; triptych-variation defaults -- see kinder.core/mutate-pane for semantics
   :mutations 20 :min-depth 0 :max-depth 4 :min-dim 3
   ;; coordinated-circles defaults -- see kinder.core/coordinated-circles
   :coordinated-circles true
   :circle-count 8 :jitter-along 0.3 :jitter-perp 0.2
   :amplitude 0.25 :frequency 1.0
   ;; Dev overlay: draws the curve as a faint dashed line
   :show-curve false})

(defn- random-seed []
  (-> (Random.) .nextLong))

(defn- render-single [{:keys [width height unit stroke-weight palette seed]}]
  (let [pal  (get palette-map palette)
        pane (cli/make-pane [width height] seed pal)]
    (svg/render pane unit stroke-weight)))

(defn- render-triptych [{:keys [width height unit stroke-weight palette gap seed mode]}]
  (let [pal       (get palette-map palette)
        center-w  (if (= "triptych-equal" mode) width (* 2 width))
        left      (cli/make-pane [width height] seed pal)
        center    (cli/make-pane [center-w height] (+ seed 1) pal)
        right     (cli/make-pane [width height] (+ seed 2) pal)]
    (svg/render-triptych left center right unit stroke-weight gap)))

(defn- render-triptych-variation
  [{:keys [width height unit stroke-weight palette gap seed
           mutations min-depth max-depth min-dim
           coordinated-circles circle-count
           jitter-along jitter-perp amplitude frequency
           show-curve]}]
  (let [pal    (get palette-map palette)
        base   (cli/make-pane [width height] seed pal)
        mopts  {:n-mutations mutations
                :min-depth   min-depth
                :max-depth   max-depth
                :min-dim     min-dim}
        panes  {:left   (core/mutate-pane base (+ seed 1) pal mopts)
                :center (core/mutate-pane base (+ seed 2) pal mopts)
                :right  (core/mutate-pane base (+ seed 3) pal mopts)}
        panes  (if coordinated-circles
                 (let [{:keys [circles curves]}
                       (core/coordinated-circles
                         {:panel-w width :center-w width :panel-h height :gap gap
                          :seed (+ seed 100) :palette pal
                          :n            circle-count
                          :jitter-along jitter-along
                          :jitter-perp  jitter-perp
                          :amplitude    amplitude
                          :frequency    frequency})
                       attach (fn [pane k]
                                (cond-> (assoc pane :circles (get circles k))
                                  show-curve (assoc :curve-points (get curves k))))]
                   {:left   (attach (:left panes)   :left)
                    :center (attach (:center panes) :center)
                    :right  (attach (:right panes)  :right)})
                 panes)]
    (svg/render-triptych (:left panes) (:center panes) (:right panes)
                         unit stroke-weight gap)))

(def ^:private modes #{"single" "triptych" "triptych-equal" "triptych-variation"})

;; kinder.core uses the random-seed library, which stores its RNG in a
;; single global var. Concurrent requests call `set-random-seed!` on top
;; of each other, corrupting in-flight generations. Serializing here is
;; pragmatic -- generation is fast and this is a single-user dev app.
(def ^:private gen-lock (Object.))

(defn- build [params]
  (locking gen-lock
    (let [seed   (or (:seed params) (random-seed))
          mode   (get modes (:mode params) "triptych-variation")
          params (merge defaults params {:seed seed :mode mode})
          ;; Bind the subdivision density knobs here so every render path
          ;; (single/triptych/variation → make-pane → generate-pane →
          ;; mutate-pane) sees the caller's values without needing every
          ;; wrapper to thread them as kwargs.
          body   (binding [core/empty-weight-scale (double (:empty-weight-scale params))
                           core/divisor-bias       (double (:divisor-bias params))
                           core/cut-direction-bias (double (:cut-direction-bias params))
                           core/corner-radius      (double (:corner-radius params))]
                   (case mode
                     "triptych-variation"          (render-triptych-variation params)
                     ("triptych" "triptych-equal") (render-triptych params)
                     (render-single params)))]
      ;; Return seed as a string: a Clojure long can exceed JS Number's
      ;; 2^53 safe-integer range, and a lossy round-trip would re-seed to
      ;; a nearby long, producing a different image on re-submission.
      {:svg body :seed (str seed) :mode mode})))

(defn- save [params]
  (let [{:keys [svg seed]} (build params)
        path (cli/output-path seed)]
    (io/make-parents path)
    (spit path svg)
    {:path path :seed seed}))

;; -- inspiration --

(def ^:private inspiration-dir "inspiration")
(def ^:private image-exts #{"jpg" "jpeg" "png" "gif" "webp"})

(defn- ext-of [name]
  (let [i (.lastIndexOf ^String name ".")]
    (when (pos? i) (str/lower-case (subs name (inc i))))))

(defn- list-inspiration []
  (let [dir (io/file inspiration-dir)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isFile ^java.io.File %))
           (map #(.getName ^java.io.File %))
           (filter #(contains? image-exts (ext-of %)))
           sort
           vec)
      [])))

(def ^:private content-types
  {"jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "png"  "image/png"
   "gif"  "image/gif"
   "webp" "image/webp"})

(defn- inspiration-file-response [filename]
  (if (or (str/includes? filename "/")
          (str/includes? filename "\\")
          (str/includes? filename ".."))
    {:status 400 :body "bad filename"}
    (let [f (io/file inspiration-dir filename)
          ext (ext-of filename)]
      (if (and (.isFile f) (contains? image-exts ext))
        {:status 200
         :headers {"content-type" (get content-types ext)
                   "cache-control" "max-age=3600"}
         :body (io/input-stream f)}
        {:status 404 :body "not found"}))))

;; -- http --

(defn- parse-query [qs]
  (if (str/blank? qs)
    {}
    (into {}
      (for [pair (str/split qs #"&")]
        (let [[k v] (str/split pair #"=" 2)]
          [(keyword k) (URLDecoder/decode (or v "") "UTF-8")])))))

(defn- parse-long-opt [v]
  (when (and v (not (str/blank? (str v))))
    (if (number? v) (long v) (Long/parseLong v))))

(defn- parse-double-opt [v]
  (when (and v (not (str/blank? (str v))))
    (if (number? v) (double v) (Double/parseDouble v))))

(defn- parse-bool-opt [v]
  (when (and (some? v) (not (str/blank? (str v))))
    (contains? #{"true" "1" "on" "yes"} (str/lower-case (str v)))))

(defn- coerce [{:keys [mode seed width height unit gap stroke-weight
                       empty-weight-scale divisor-bias cut-direction-bias
                       corner-radius
                       mutations min-depth max-depth min-dim
                       coordinated-circles circle-count
                       jitter-along jitter-perp amplitude frequency show-curve]
                :as params}]
  (cond-> {}
    mode                                  (assoc :mode mode)
    (parse-long-opt seed)                 (assoc :seed (parse-long-opt seed))
    (parse-long-opt width)                (assoc :width (parse-long-opt width))
    (parse-long-opt height)               (assoc :height (parse-long-opt height))
    (parse-long-opt unit)                 (assoc :unit (parse-long-opt unit))
    (parse-double-opt gap)                (assoc :gap (parse-double-opt gap))
    (parse-double-opt stroke-weight)      (assoc :stroke-weight (parse-double-opt stroke-weight))
    (parse-double-opt empty-weight-scale) (assoc :empty-weight-scale (parse-double-opt empty-weight-scale))
    (parse-double-opt divisor-bias)       (assoc :divisor-bias (parse-double-opt divisor-bias))
    (parse-double-opt cut-direction-bias) (assoc :cut-direction-bias (parse-double-opt cut-direction-bias))
    (parse-double-opt corner-radius)      (assoc :corner-radius (parse-double-opt corner-radius))
    (parse-long-opt mutations)            (assoc :mutations (parse-long-opt mutations))
    (parse-long-opt min-depth)            (assoc :min-depth (parse-long-opt min-depth))
    (parse-long-opt max-depth)            (assoc :max-depth (parse-long-opt max-depth))
    (parse-long-opt min-dim)              (assoc :min-dim (parse-long-opt min-dim))
    (contains? params :coordinated-circles)
    (assoc :coordinated-circles (boolean (parse-bool-opt coordinated-circles)))
    (parse-long-opt circle-count)         (assoc :circle-count (parse-long-opt circle-count))
    (parse-double-opt jitter-along)       (assoc :jitter-along (parse-double-opt jitter-along))
    (parse-double-opt jitter-perp)        (assoc :jitter-perp (parse-double-opt jitter-perp))
    (parse-double-opt amplitude)          (assoc :amplitude (parse-double-opt amplitude))
    (parse-double-opt frequency)          (assoc :frequency (parse-double-opt frequency))
    (contains? params :show-curve)
    (assoc :show-curve (boolean (parse-bool-opt show-curve)))))

(defn- json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-str body)})

(defn- html-response [body]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn- handler [{:keys [uri request-method query-string body]}]
  (cond
    (and (= :get request-method) (= "/" uri))
    (html-response (slurp (io/resource "public/index.html")))

    (and (= :get request-method) (= "/generate" uri))
    (json-response 200 (build (coerce (parse-query query-string))))

    (and (= :post request-method) (= "/save" uri))
    (let [payload (json/read-str (slurp body) :key-fn keyword)]
      (json-response 200 (save (coerce payload))))

    (and (= :get request-method) (= "/inspiration" uri))
    (json-response 200 {:images (list-inspiration)})

    (and (= :get request-method) (str/starts-with? uri "/inspiration/"))
    (inspiration-file-response (URLDecoder/decode (subs uri (count "/inspiration/")) "UTF-8"))

    :else
    {:status 404 :headers {"content-type" "text/plain"} :body "not found"}))

(defn -main [& _]
  (let [port 8080]
    (http/run-server #'handler {:port port})
    (println (str "kinder web listening on http://localhost:" port))
    @(promise)))
