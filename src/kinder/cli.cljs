(ns kinder.cli
  "Node CLI — parses flags, normalizes via kinder.options, builds a scene
  via kinder.layouts, writes the rendered SVG to disk."
  (:require [kinder.layouts :as layouts]
            [kinder.options :as opts]
            [kinder.rng :as rng]
            [clojure.tools.cli :as tools-cli]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def ^:private cli-options
  [["-W" "--width WIDTH"            "Canvas width in units"          :id :width]
   ["-H" "--height HEIGHT"          "Canvas height in units"         :id :height]
   ["-u" "--unit UNIT"              "Pixels per unit"                :id :unit]
   ["-s" "--seed SEED"              "Fix the seed (string)"          :id :seed]
   ["-p" "--palette NAME"           "Palette: kinder | anthro-1 | anthro-2" :id :palette]
   ["-o" "--out PATH"               "Output SVG path"                :id :out]
   ["-n" "--count N"                "Number of pieces (single layout)" :id :count]
   ["-l" "--layout NAME"            "Layout: single | triptych | triptych-equal | triptych-variation"
    :id :mode
    :validate [opts/layouts "Must be single, triptych, triptych-equal, or triptych-variation"]]
   [nil  "--stroke-weight NUM"      "Border thickness in canvas units" :id :stroke-weight]
   [nil  "--empty-weight-scale NUM" "Multiplier on subdivision stop-weights"  :id :empty-weight-scale]
   [nil  "--divisor-bias NUM"       "Exponent shaping even-split divisor choice" :id :divisor-bias]
   [nil  "--cut-direction-bias NUM" "Exponent shaping short-axis cut preference" :id :cut-direction-bias]
   [nil  "--corner-radius NUM"      "Cell corner radius in output pixels"    :id :corner-radius]
   [nil  "--gap NUM"                "Gap between triptych panes (units)"     :id :gap]
   [nil  "--mutations INT"          "Subtrees to regenerate per panel"       :id :mutations]
   [nil  "--min-depth INT"          "Shallowest mutatable depth"             :id :min-depth]
   [nil  "--max-depth INT"          "Deepest mutatable depth"                :id :max-depth]
   [nil  "--min-dim INT"            "Minimum rect dim to mutate"             :id :min-dim]
   [nil  "--[no-]coordinated-circles" "Coordinated-circles curve on/off"      :id :coordinated-circles]
   [nil  "--circle-count INT"       "Total circles across the triptych"      :id :circle-count]
   [nil  "--jitter-along NUM"       "Along-curve wander (fraction of spacing)" :id :jitter-along]
   [nil  "--jitter-perp NUM"        "Perp wander (fraction of panel height)" :id :jitter-perp]
   [nil  "--amplitude NUM"          "Sine amplitude (fraction of panel height)" :id :amplitude]
   [nil  "--frequency NUM"          "Sine cycles across triptych width"      :id :frequency]
   [nil  "--show-curve"             "Overlay the coordinated-circles curve"  :id :show-curve]
   ["-O" "--open"                   "Open each SVG after generating"         :id :open]
   ["-h" "--help"                   "Show help"                              :id :help]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IO
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ensure-dir! [filepath]
  (let [dir (.dirname path filepath)]
    (when-not (.existsSync fs dir)
      (.mkdirSync fs dir #js {:recursive true}))))

(defn- default-path [seed]
  (.join path "output" "svg" (opts/output-filename seed)))

(defn- write-svg! [filepath content]
  (ensure-dir! filepath)
  (.writeFileSync fs filepath content)
  filepath)

(defn- open-command
  "Best-effort cross-platform 'open in default app' command name."
  []
  (case (.-platform js/process)
    "darwin" "open"
    "win32"  "start"
    "xdg-open"))

(defn- maybe-open! [filepath open?]
  (when open?
    (.spawn cp (open-command) #js [filepath] #js {:stdio "ignore"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- generate-one! [o out-override]
  (let [seed     (or (:seed o) (rng/ambient-seed))
        o        (assoc o :seed seed)
        scene    (layouts/generate-scene o)
        svg-str  (layouts/render-scene scene o)
        filepath (or out-override (default-path seed))]
    (write-svg! filepath svg-str)
    (println filepath)
    (maybe-open! filepath (:open o))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn main [& args]
  (let [{:keys [options errors summary]}
        (tools-cli/parse-opts (vec args) cli-options)]
    (cond
      (:help options)
      (println summary)

      (seq errors)
      (do (doseq [e errors] (println e))
          (.exit js/process 1))

      :else
      (let [cnt (let [c (:count options)]
                  (if (or (nil? c) (str/blank? (str c)))
                    1
                    (js/parseInt (str c) 10)))
            open?       (boolean (:open options))
            out         (:out options)
            ;; Drop CLI-only keys before normalizing so opts/normalize
            ;; doesn't treat them as unknown values to coerce.
            user-input  (dissoc options :count :out :open :help)
            o           (-> (opts/normalize user-input)
                            (assoc :open open?))
            seed-fixed? (some? (:seed o))]
        (when (and out (> cnt 1))
          (println "Warning: --out with --count > 1 reuses the same path and overwrites each time"))
        (if (= "single" (:mode o))
          (let [n (if (and seed-fixed? (> cnt 1))
                    (do (println "Warning: --seed ignores --count; generating one piece") 1)
                    cnt)]
            (dotimes [i n]
              (generate-one! (if (zero? i) o (dissoc o :seed)) out)))
          (generate-one! o out))))))
