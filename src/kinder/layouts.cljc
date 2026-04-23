(ns kinder.layouts
  "Scene assembly and rendering shared between browser and CLI.

  A `scene` is `{:mode <mode-string> :panes [pane ...]}` plus the options
  needed to render it. `generate-scene` turns a normalized options map
  into a scene; `render-scene` turns a scene (optionally clipped at a
  render-depth) into an SVG string.

  This is the namespace TO-CLJC.md called out for deduplication — the
  layout dispatch (single / triptych / triptych-equal / triptych-
  variation), sub-seed derivation, and coordinated-circle wiring all
  live here so `browser.cljs` and `cli.cljs` are thin callers."
  (:require [kinder.core :as core]
            [kinder.options :as opts]
            [kinder.svg :as svg]))

(defn- gen-pane [o pal dim seed]
  (core/generate-pane dim
                      :seed               seed
                      :palette            pal
                      :empty-weight-scale (:empty-weight-scale o)
                      :divisor-bias       (:divisor-bias o)
                      :cut-direction-bias (:cut-direction-bias o)
                      :corner-radius      (:corner-radius o)))

(defn- mutate-opts [o]
  {:n-mutations        (:mutations o)
   :min-depth          (:min-depth o)
   :max-depth          (:max-depth o)
   :min-dim            (:min-dim o)
   :empty-weight-scale (:empty-weight-scale o)
   :divisor-bias       (:divisor-bias o)
   :cut-direction-bias (:cut-direction-bias o)
   :corner-radius      (:corner-radius o)})

(defn- attach-coordinated-circles
  "Replace each panel's independent circles with circles sampled from a
  curve spanning the triptych. When `:show-curve` is on, also attach the
  underlying curve polyline for the dev overlay."
  [{:keys [left center right]} o pal]
  (let [{:keys [width height gap seed show-curve circle-count
                jitter-along jitter-perp amplitude frequency]} o
        {:keys [circles curves]}
        (core/coordinated-circles
          {:panel-w  width
           :center-w width
           :panel-h  height
           :gap      gap
           :seed     (str seed "/cc")
           :palette  pal
           :n            circle-count
           :jitter-along jitter-along
           :jitter-perp  jitter-perp
           :amplitude    amplitude
           :frequency    frequency})
        attach (fn [p k]
                 (cond-> (assoc p :circles (get circles k))
                   show-curve (assoc :curve-points (get curves k))))]
    {:left   (attach left   :left)
     :center (attach center :center)
     :right  (attach right  :right)}))

(defn generate-scene
  "Build the panes for the requested mode. Expects a fully-normalized
  options map (see `kinder.options/normalize`) including `:seed`."
  [{:keys [mode width height seed palette coordinated-circles] :as o}]
  (let [pal (opts/palette-of palette)]
    (case mode
      "single"
      {:mode  "single"
       :panes [(gen-pane o pal [width height] seed)]}

      ("triptych" "triptych-equal")
      (let [cw (if (= mode "triptych-equal") width (* 2 width))
            l  (gen-pane o pal [width height] (str seed "/l"))
            c  (gen-pane o pal [cw    height] (str seed "/c"))
            r  (gen-pane o pal [width height] (str seed "/r"))]
        {:mode mode :panes [l c r]})

      "triptych-variation"
      (let [base   (gen-pane o pal [width height] seed)
            mopts  (mutate-opts o)
            panes0 {:left   (core/mutate-pane base (str seed "/l") pal mopts)
                    :center (core/mutate-pane base (str seed "/c") pal mopts)
                    :right  (core/mutate-pane base (str seed "/r") pal mopts)}
            {:keys [left center right]} (if coordinated-circles
                                          (attach-coordinated-circles panes0 o pal)
                                          panes0)]
        {:mode "triptych-variation" :panes [left center right]}))))

(def ^:private full-depth
  "Effectively-infinite depth — the generator trees never approach this
  so passing it to `take-depth` is equivalent to rendering the full
  scene. Used when the caller doesn't care about progressive reveal."
  10000)

(defn- pane-rects-done-at? [depth pane]
  (= (:rect pane) (core/take-rect-depth depth (:rect pane))))

(defn scene-rects-done-at?
  "True when every pane's rect tree is fully revealed at `depth`. Used
  by the animation loop so circles stay hidden until the last panel's
  rectangles finish appearing."
  [{:keys [panes]} depth]
  (every? (partial pane-rects-done-at? depth) panes))

(defn total-circles
  "Total number of circles across every pane in `scene`."
  [{:keys [panes]}]
  (reduce + 0 (map #(count (:circles %)) panes)))

(defn- truncate-circles
  "Return `panes` with :circles reduced so that only the first `n` total
  circles (taken in pane order) remain visible."
  [panes n]
  (loop [remaining n, ps panes, acc []]
    (if (empty? ps)
      acc
      (let [p (first ps)
            cs (:circles p)
            taken (min (count cs) remaining)]
        (recur (- remaining taken)
               (rest ps)
               (conj acc (assoc p :circles (vec (take taken cs)))))))))

(defn render-scene
  "Render a scene as an SVG string.

  - `depth` clips the render tree for the rect-reveal phase; omit it to
    render the full scene.
  - `visible-circles` caps how many circles are drawn (after rects
    finish); omit for all.

  Circles (and the coordinated-circle curve overlay) are suppressed
  until every pane's rectangles are done at the given depth, so triptych
  panels finish rect-reveal together before any circle lands."
  ([scene o] (render-scene scene o full-depth))
  ([scene o depth] (render-scene scene o depth (total-circles scene)))
  ([{:keys [mode] :as scene} {:keys [unit stroke-weight gap]} depth visible-circles]
   (let [rects-done? (scene-rects-done-at? scene depth)
         n-circles   (if rects-done? (max 0 visible-circles) 0)
         panes'      (truncate-circles (:panes scene) n-circles)
         prep        (fn [pane]
                       (cond-> (core/take-depth depth pane)
                         (not rects-done?) (dissoc :curve-points)))]
     (case mode
       "single"
       (svg/render (prep (first panes')) unit stroke-weight)

       ("triptych" "triptych-equal" "triptych-variation")
       (let [[l c r] panes']
         (svg/render-triptych (prep l) (prep c) (prep r)
                              unit stroke-weight gap))))))

(defn scene-done-at?
  "True when every pane in `scene` is fully revealed at `depth`."
  [{:keys [panes]} depth]
  (every? #(= % (core/take-depth depth %)) panes))
