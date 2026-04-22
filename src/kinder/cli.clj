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

(def ^:private layouts #{"single" "triptych" "triptych-equal" "triptych-variation"})

(def ^:private cli-options
  [["-W" "--width INT"    "Canvas width in units"          :default 30  :parse-fn #(Integer/parseInt %)]
   ["-H" "--height INT"   "Canvas height in units"         :default 70  :parse-fn #(Integer/parseInt %)]
   ["-u" "--unit INT"     "Pixels per unit"                :default 10  :parse-fn #(Integer/parseInt %)]
   ["-s" "--seed INT"     "Fix the random seed"            :parse-fn #(Long/parseLong %)]
   ["-p" "--palette NAME" "Palette: kinder | red | orange" :default "kinder"
    :validate [#(contains? palette-map %) "Must be kinder, red, or orange"]]
   ["-o" "--out PATH"     "Output SVG path (single piece only)"]
   ["-n" "--count INT"    "Number of pieces to generate"   :default 1   :parse-fn #(Integer/parseInt %)]
   ["-l" "--layout NAME"  "Layout: single | triptych | triptych-equal | triptych-variation" :default "triptych-variation"
    :validate [layouts "Must be single, triptych, triptych-equal, or triptych-variation"]]
   ;; Border / "lead" thickness in canvas units. At unit=10, 0.2 = 2px,
   ;; 0.5 = 5px, etc. Heavier values give a stained-glass / leaded look.
   [nil "--stroke-weight NUM" "Border line thickness in canvas units"
    :default 0.2 :parse-fn #(Double/parseDouble %)]
   ;; --- subdivision density (all modes) -----------------------------------
   ;; Multiplier on the "stop subdividing" weight in every size-branched
   ;; cond in kinder.core/children. 1.0 preserves current behavior; <1
   ;; produces fewer empty rects (denser); >1 more empty rects.
   [nil "--empty-weight-scale NUM" "Multiplier on subdivision stop-weights"
    :default 1.0 :parse-fn #(Double/parseDouble %)]
   ;; Exponent on the divisor-selection weighting in horz/vert-even-children.
   ;; 1.0 preserves current behavior (small divisors favored). 0 = uniform.
   ;; Negative = large divisors favored (chunkier splits).
   [nil "--divisor-bias NUM"       "Exponent shaping even-split divisor choice"
    :default 1.0 :parse-fn #(Double/parseDouble %)]
   ;; Exponent on short-axis-cut preference in make-direct-children.
   ;; 1.0 = current 10:1 bias. 0 = 50/50. Negative flips -- long-axis
   ;; cuts favored (vertical mullions on tall panes).
   [nil "--cut-direction-bias NUM" "Exponent shaping short-axis cut preference"
    :default 1.0 :parse-fn #(Double/parseDouble %)]
   ;; Corner radius in output pixels applied to each subdivided cell.
   ;; Outer pane frame stays square regardless. 0 = square corners
   ;; everywhere; larger = more pronounced rounding.
   [nil "--corner-radius NUM" "Cell corner radius in output pixels"
    :default 2.0 :parse-fn #(Double/parseDouble %)]
   ;; --- triptych-variation tuning (ignored for other layouts) ---
   ;; How many subtrees to regenerate per panel. 0 gives identical panels;
   ;; higher values lose the family resemblance.
   [nil "--mutations INT" "Subtrees to regenerate per panel (triptych-variation)"
    :default 20 :parse-fn #(Integer/parseInt %)]
   ;; Shallowest depth a mutation can target. 1 excludes the root (which would
   ;; just produce an independent panel).
   [nil "--min-depth INT" "Shallowest mutatable depth (triptych-variation)"
    :default 0 :parse-fn #(Integer/parseInt %)]
   ;; Deepest depth a mutation can target. Smaller values = bigger visible
   ;; changes; larger values = subtler local changes.
   [nil "--max-depth INT" "Deepest mutatable depth (triptych-variation)"
    :default 4 :parse-fn #(Integer/parseInt %)]
   ;; Minimum rect dimension (in units) to be a mutation target. Skips tiny
   ;; rects where a regenerated subtree wouldn't be visible.
   [nil "--min-dim INT"   "Minimum rect dim to mutate (triptych-variation)"
    :default 3 :parse-fn #(Integer/parseInt %)]
   ;; --- coordinated-circles tuning (triptych-variation only) ---
   ;; When on, replaces each panel's independent circles with circles sampled
   ;; along a shared curve spanning the triptych. Curve angle and phase are
   ;; randomized per seed; amplitude/frequency below shape the wave.
   [nil "--[no-]coordinated-circles" "Place circles along a shared curve (triptych-variation). Default on."
    :default true]
   [nil "--circle-count INT"    "Total circles across the triptych"
    :default 8 :parse-fn #(Integer/parseInt %)]
   [nil "--jitter-along NUM"    "Along-curve wander, fraction of spacing"
    :default 0.3 :parse-fn #(Double/parseDouble %)]
   [nil "--jitter-perp NUM"     "Perp wander, fraction of panel height"
    :default 0.2 :parse-fn #(Double/parseDouble %)]
   [nil "--amplitude NUM"       "Sine amplitude, fraction of panel height (0 = straight line)"
    :default 0.25 :parse-fn #(Double/parseDouble %)]
   [nil "--frequency NUM"       "Sine cycles across triptych width"
    :default 1.0 :parse-fn #(Double/parseDouble %)]
   ;; Dev overlay: draws the underlying curve as a faint dashed polyline
   ;; so you can see where coordinated circles are supposed to land.
   [nil "--show-curve"          "Overlay the coordinated-circles curve as a faint line (dev)"]
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
  (let [{:keys [width height unit palette out open stroke-weight]} opts
        pal      (get palette-map palette)
        seed     (or seed (-> (Random.) .nextLong))
        pane     (make-pane [width height] seed pal)
        out-file (or out (output-path seed))]
    (io/make-parents out-file)
    (spit out-file (svg/render pane unit stroke-weight))
    (finish! out-file open)))

(defn- generate-triptych [opts seed center-multiplier]
  (let [{:keys [width height unit palette out open stroke-weight]} opts
        gap      6
        pal      (get palette-map palette)
        seed     (or seed (-> (Random.) .nextLong))
        left     (make-pane [width height] seed pal)
        center   (make-pane [(* center-multiplier width) height] (+ seed 1) pal)
        right    (make-pane [width height] (+ seed 2) pal)
        out-file (or out (output-path seed))]
    (io/make-parents out-file)
    (spit out-file (svg/render-triptych left center right unit stroke-weight gap))
    (finish! out-file open)))

(defn- with-coordinated-circles
  "Overwrites each pane's :circles with circles sampled from a curve
  spanning the triptych. Uses a seed offset from the master so toggling
  this option doesn't perturb the underlying pane mutations. When
  :show-curve is on, also attaches :curve-points to each pane for the
  dev overlay polyline."
  [{:keys [left center right]} {:keys [width height seed pal gap opts]}]
  (let [{:keys [circles curves]}
        (core/coordinated-circles
          {:panel-w width :center-w width :panel-h height :gap gap
           :seed (+ seed 100) :palette pal
           :n            (:circle-count opts)
           :jitter-along (:jitter-along opts)
           :jitter-perp  (:jitter-perp opts)
           :amplitude    (:amplitude opts)
           :frequency    (:frequency opts)})
        attach (fn [pane k]
                 (cond-> (assoc pane :circles (get circles k))
                   (:show-curve opts) (assoc :curve-points (get curves k))))]
    {:left   (attach left   :left)
     :center (attach center :center)
     :right  (attach right  :right)}))

(defn- generate-variation-triptych
  "Three panels that share a base skeleton, each with localized subtree
  mutations driven by sub-seeds derived from the master seed."
  [opts seed]
  (let [{:keys [width height unit palette out open stroke-weight
                mutations min-depth max-depth min-dim
                coordinated-circles]} opts
        gap      6
        pal      (get palette-map palette)
        seed     (or seed (-> (Random.) .nextLong))
        base     (make-pane [width height] seed pal)
        mopts    {:n-mutations mutations
                  :min-depth   min-depth
                  :max-depth   max-depth
                  :min-dim     min-dim}
        ;; +1/+2/+3 so left is also a variation (not the raw base) --
        ;; all three panels are siblings, none privileged.
        panes    {:left   (core/mutate-pane base (+ seed 1) pal mopts)
                  :center (core/mutate-pane base (+ seed 2) pal mopts)
                  :right  (core/mutate-pane base (+ seed 3) pal mopts)}
        panes    (if coordinated-circles
                   (with-coordinated-circles
                     panes
                     {:width width :height height :seed seed :pal pal :gap gap :opts opts})
                   panes)
        out-file (or out (output-path seed))]
    (io/make-parents out-file)
    (spit out-file (svg/render-triptych (:left panes) (:center panes) (:right panes)
                                        unit stroke-weight gap))
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
      ;; Bind the density knobs once at the CLI entry so every generate
      ;; path below (make-pane → generate-pane → mutate-pane) sees them.
      (binding [core/empty-weight-scale (double (:empty-weight-scale options))
                core/divisor-bias       (double (:divisor-bias options))
                core/cut-direction-bias (double (:cut-direction-bias options))
                core/corner-radius      (double (:corner-radius options))]
        (case (:layout options)
          "triptych"           (generate-triptych options (:seed options) 2)
          "triptych-equal"     (generate-triptych options (:seed options) 1)
          "triptych-variation" (generate-variation-triptych options (:seed options))
          "single"
          (let [{:keys [seed count]} options
                n (if (and seed (> count 1)) 1 count)]
            (when (and seed (> count 1))
              (println "Warning: --seed ignores --count; generating one piece"))
            (dotimes [i n]
              (generate-one options (when (zero? i) seed)))
            (System/exit 0)))))))
