(ns kinder.options
  "Single source of truth for generator defaults and input normalization.
  Browser UI and Node CLI both funnel user-supplied values through
  `normalize` before calling `kinder.core`."
  (:require [clojure.string :as str]
            [kinder.core :as core]))

(def palettes
  {"kinder" core/kinder-palette
   "anthro-1" core/anthro-1-palette
   "anthro-2" core/anthro-2-palette
   "anthro-3" core/anthro-3-palette})

(def layouts #{"single" "triptych" "triptych-equal" "triptych-variation" "qr"})

(def qr-error-levels #{"L" "M" "Q" "H"})

(def defaults
  {:mode                "triptych-variation"
   :width               30
   :height              70
   :unit                10
   :gap                 6
   :stroke-weight       0.2
   :palette             "kinder"
   :empty-weight-scale  1.0
   :divisor-bias        1.0
   :cut-direction-bias  1.0
   :corner-radius       2.0
   :mutations           20
   :min-depth           0
   :max-depth           4
   :min-dim             3
   :coordinated-circles true
   :circle-count        8
   :jitter-along        0.3
   :jitter-perp         0.2
   :amplitude           0.25
   :frequency           1.0
   :show-curve          false
   :animate             true
   :reveal-step-ms      60
   :seed                nil
   :text                "https://kinder.art"
   :qr-ecl              "H"
   :qr-quiet-zone       4})

(def share-keys
  [:seed
   :mode
   :palette
   :width
   :height
   :unit
   :gap
   :stroke-weight
   :corner-radius
   :empty-weight-scale
   :divisor-bias
   :cut-direction-bias
   :mutations
   :min-depth
   :max-depth
   :min-dim
   :coordinated-circles
   :circle-count
   :jitter-along
   :jitter-perp
   :amplitude
   :frequency
   :show-curve
   :text
   :qr-ecl
   :qr-quiet-zone])

(def ^:private share-key-set (set share-keys))

(def ^:private share-param-names
  {:seed                "s"
   :mode                "m"
   :palette             "p"
   :width               "w"
   :height              "h"
   :unit                "u"
   :gap                 "g"
   :stroke-weight       "sw"
   :corner-radius       "cr"
   :empty-weight-scale  "ews"
   :divisor-bias        "db"
   :cut-direction-bias  "cdb"
   :mutations           "mu"
   :min-depth           "mind"
   :max-depth           "maxd"
   :min-dim             "minw"
   :coordinated-circles "cc"
   :circle-count        "cn"
   :jitter-along        "ja"
   :jitter-perp         "jp"
   :amplitude           "a"
   :frequency           "f"
   :show-curve          "sc"
   :text                "t"
   :qr-ecl              "e"
   :qr-quiet-zone       "qz"})

(def ^:private share-param-keys
  (reduce-kv (fn [acc k v] (assoc acc v k)) {} share-param-names))

(defn- blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- to-int [v]
  (cond
    (blank? v)  nil
    (number? v) (long v)
    :else #?(:clj  (Long/parseLong (str v))
             :cljs (let [n (js/parseInt (str v) 10)]
                     (when-not (js/isNaN n) n)))))

(defn- to-double [v]
  (cond
    (blank? v)  nil
    (number? v) (double v)
    :else #?(:clj  (Double/parseDouble (str v))
             :cljs (let [n (js/parseFloat (str v))]
                     (when-not (js/isNaN n) n)))))

(defn- to-bool [v]
  (cond
    (nil? v)        nil
    (boolean? v)    v
    (number? v)     (not (zero? v))
    (string? v)     (contains? #{"true" "1" "on" "yes"} (str/lower-case v))
    :else           nil))

(def ^:private int-keys
  #{:width :height :unit :mutations :min-depth :max-depth :min-dim :circle-count
    :reveal-step-ms :qr-quiet-zone})

(def ^:private double-keys
  #{:gap :stroke-weight :empty-weight-scale :divisor-bias :cut-direction-bias
    :corner-radius :jitter-along :jitter-perp :amplitude :frequency})

(def ^:private bool-keys
  #{:coordinated-circles :show-curve :animate})

(defn- coerce-value [k v]
  (cond
    (= k :seed)     (when-not (blank? v) (str v))
    (= k :mode)     (when v (str v))
    (= k :palette)  (when v (str v))
    (= k :text)     (when v (str v))
    (= k :qr-ecl)   (when v
                      (let [s (str/upper-case (str v))]
                        (if (contains? qr-error-levels s) s "H")))
    (int-keys k)    (to-int v)
    (double-keys k) (to-double v)
    (bool-keys k)   (to-bool v)
    :else           v))

(defn normalize
  "Merge `user-input` onto `defaults`, coercing each known key to its
  expected type. Unknown keys pass through untouched."
  [user-input]
  (let [merged (merge defaults user-input)]
    (reduce-kv
      (fn [acc k v]
        (let [coerced (coerce-value k v)]
          (assoc acc k (if (and (nil? coerced) (contains? defaults k))
                         (get defaults k)
                         coerced))))
      {}
      merged)))

(defn- share-param-value [v]
  (cond
    (nil? v)         nil
    (boolean? v)     (if v "true" "false")
    :else            (str v)))

(defn- relevant-share-keys
  [{:keys [mode coordinated-circles]}]
  (case mode
    "qr"
    [:seed
     :mode
     :palette
     :unit
     :stroke-weight
     :corner-radius
     :text
     :qr-ecl
     :qr-quiet-zone]

    "triptych-variation"
    (into [:seed
           :mode
           :palette
           :width
           :height
           :unit
           :gap
           :stroke-weight
           :corner-radius
           :empty-weight-scale
           :divisor-bias
           :cut-direction-bias
           :mutations
           :min-depth
           :max-depth
           :min-dim
           :coordinated-circles]
          (when coordinated-circles
            [:circle-count
             :jitter-along
             :jitter-perp
             :amplitude
             :frequency
             :show-curve]))

    ("single" "triptych" "triptych-equal")
    [:seed
     :mode
     :palette
     :width
     :height
     :unit
     :gap
     :stroke-weight
     :corner-radius
     :empty-weight-scale
     :divisor-bias
     :cut-direction-bias]

    share-keys))

(defn share-param-pairs
  "Return canonical query-param pairs for a fully-specified artwork URL."
  [o]
  (let [normalized (normalize o)]
    (into []
          (keep (fn [k]
                  (let [v (get normalized k)]
                    (when (or (= k :seed)
                              (not= v (get defaults k)))
                      (when-let [encoded (share-param-value v)]
                        [(get share-param-names k (name k)) encoded])))))
          (relevant-share-keys normalized))))

(defn share-query-input
  "Filter a string-keyed query map down to recognized share params."
  [query]
  (reduce-kv
    (fn [acc k v]
      (let [kw (or (get share-param-keys k)
                   (keyword k))]
        (if (share-key-set kw)
          (assoc acc kw v)
          acc)))
    {}
    query))

(defn palette-of
  "Resolve a palette name to a palette map. Falls back to the kinder palette."
  ([name]
   (palette-of name nil))
  ([name _seed]
   (or (get palettes name) core/kinder-palette)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Filename derivation — shared between browser save and Node CLI.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- pad [n len]
  (let [s (str n)
        need (- len (count s))]
    (if (pos? need)
      (str (apply str (repeat need \0)) s)
      s)))

(defn timestamp
  "Local-time timestamp like 2026-04-22-143022142, identical across CLJ/CLJS."
  []
  #?(:clj  (let [now (java.time.LocalDateTime/now)
                 ms  (quot (.getNano now) 1000000)]
             (str (.getYear now) "-"
                  (pad (.getMonthValue now) 2) "-"
                  (pad (.getDayOfMonth now) 2) "-"
                  (pad (.getHour now) 2)
                  (pad (.getMinute now) 2)
                  (pad (.getSecond now) 2)
                  (pad ms 3)))
     :cljs (let [d (js/Date.)]
             (str (.getFullYear d) "-"
                  (pad (inc (.getMonth d)) 2) "-"
                  (pad (.getDate d) 2) "-"
                  (pad (.getHours d) 2)
                  (pad (.getMinutes d) 2)
                  (pad (.getSeconds d) 2)
                  (pad (.getMilliseconds d) 3)))))

(defn- safe-seed
  "Make a seed string safe for a filename. Strips anything weird."
  [seed]
  (if seed
    (str/replace (str seed) #"[^A-Za-z0-9._-]" "_")
    "noseed"))

(defn output-filename-base
  "Suggested filename stem for saved SVGs, derived from the seed and current time."
  [seed]
  (str (timestamp) "-" (safe-seed seed)))

(defn svg-filename
  "Attach an optional suffix and the `.svg` extension to a saved filename stem."
  ([base]
   (svg-filename base ""))
  ([base suffix]
   (str base suffix ".svg")))

(defn output-filename
  "Suggested filename for a saved SVG, derived from the seed and current time."
  [seed]
  (svg-filename (output-filename-base seed)))
