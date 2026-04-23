(ns kinder.browser
  "Browser-local kinder runtime. Reads controls, generates panes and SVG
  locally using the shared CLJC generator and renderer, injects the
  result into the DOM, and handles progressive reveal, save, pan/zoom,
  and the inspiration gallery."
  (:require [kinder.layouts :as layouts]
            [kinder.options :as opts]
            [kinder.rng :as rng]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; One-atom app state (survives shadow hot-reload via defonce)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce app-state
  (atom {:scene        nil
         :opts         nil
         :anim-token   0
         :view         {:scale 1.0 :tx 0.0 :ty 0.0}
         :drag         nil
         :view-name    "generate"
         :inspiration  nil
         :fs-index     -1
         :debounce-id  nil}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DOM helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- el [id] (.getElementById js/document id))
(defn- val-of [id] (.-value (el id)))
(defn- checked? [id] (.-checked (el id)))

(defn- current-mode []
  (or (some (fn [n] (when (.-checked n) (.-value n)))
            (array-seq (.querySelectorAll js/document "input[name=mode]")))
      "triptych-variation"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form → normalized options
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private number-inputs
  {:width              "width"
   :height             "height"
   :unit               "unit"
   :gap                "gap"
   :stroke-weight      "stroke-weight"
   :corner-radius      "corner-radius"
   :empty-weight-scale "empty-weight-scale"
   :divisor-bias       "divisor-bias"
   :cut-direction-bias "cut-direction-bias"
   :mutations          "mutations"
   :min-depth          "min-depth"
   :max-depth          "max-depth"
   :min-dim            "min-dim"
   :circle-count       "circle-count"
   :jitter-along       "jitter-along"
   :jitter-perp        "jitter-perp"
   :amplitude          "amplitude"
   :frequency          "frequency"
   :reveal-step-ms     "reveal-step-ms"
   :qr-quiet-zone      "qr-quiet-zone"})

(def ^:private text-inputs
  {:text   "text"
   :qr-ecl "qr-ecl"})

(def ^:private checkbox-inputs
  {:coordinated-circles "coordinated-circles"
   :show-curve          "show-curve"
   :animate             "animate"})

;; These are display-only — they change how the current scene is
;; revealed but don't affect the generator, so we don't regenerate
;; on change.
(def ^:private display-only-keys #{:animate :reveal-step-ms})

(defn- read-form []
  (let [from-numbers  (into {} (map (fn [[k id]] [k (val-of id)]) number-inputs))
        from-text     (into {} (map (fn [[k id]] [k (val-of id)]) text-inputs))
        from-checkbox (into {} (map (fn [[k id]] [k (checked? id)]) checkbox-inputs))]
    (merge from-numbers
           from-text
           from-checkbox
           {:mode    (current-mode)
            :palette (val-of "palette")})))

(defn- current-opts [] (opts/normalize (read-form)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Progressive reveal
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- stage-insert! [svg-str]
  (set! (.-innerHTML (el "stage")) svg-str))

(defn- schedule-next! [step step-ms]
  (if (pos? step-ms)
    (js/setTimeout step step-ms)
    (js/requestAnimationFrame step)))

(defn- animate-reveal!
  "Either render the scene immediately (animation off) or walk through
  reveal frames (animation on). Reveal has two phases:
    1. rect phase: `depth` ticks up until every pane's rects are done;
    2. circle phase: `circles` ticks up from 0 to `total-circles`.
  Each call bumps :anim-token; any in-flight loop whose captured token
  doesn't match the current one exits on its next tick."
  [scene o]
  (let [token (:anim-token (swap! app-state update :anim-token inc))]
    (if-not (:animate o)
      (stage-insert! (layouts/render-scene scene o))
      (let [depth    (atom 0)
            circles  (atom 0)
            total    (layouts/total-circles scene)
            step-ms  (max 0 (or (:reveal-step-ms o) 0))
            step     (fn step []
                       (when (= token (:anim-token @app-state))
                         (stage-insert!
                           (layouts/render-scene scene o @depth @circles))
                         (cond
                           (not (layouts/scene-rects-done-at? scene @depth))
                           (do (swap! depth inc)
                               (schedule-next! step step-ms))

                           (< @circles total)
                           (do (swap! circles inc)
                               (schedule-next! step step-ms)))))]
        (step)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Top-level generate / save
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- update-seed-display! [seed mode]
  (set! (.-textContent (el "seed"))
        (str "seed: " seed " · mode: " mode)))

(defn- show-toast! [msg]
  (let [t (el "toast")]
    (set! (.-textContent t) msg)
    (.add (.-classList t) "show")
    (js/setTimeout #(.remove (.-classList t) "show") 1800)))

(defn generate!
  ([] (generate! {}))
  ([{:keys [reuse-seed?]}]
   (let [o       (current-opts)
         prior   (:opts @app-state)
         seed    (if (and reuse-seed? (:seed prior))
                   (:seed prior)
                   (rng/ambient-seed))
         o       (assoc o :seed seed)
         scene   (layouts/generate-scene o)]
     (swap! app-state assoc :scene scene :opts o)
     (update-seed-display! seed (:mode o))
     (animate-reveal! scene o))))

(defn save-blob! []
  (when-let [scene (:scene @app-state)]
    (let [o        (:opts @app-state)
          svg-str  (layouts/render-scene scene o)
          blob     (js/Blob. #js [svg-str] #js {:type "image/svg+xml"})
          url      (js/URL.createObjectURL blob)
          filename (opts/output-filename (:seed o))
          a        (.createElement js/document "a")]
      (set! (.-href a) url)
      (set! (.-download a) filename)
      (.. js/document -body (appendChild a))
      (.click a)
      (.. js/document -body (removeChild a))
      (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
      (show-toast! (str "saved " filename)))))

(defn save!
  "Prefer the native save dialog when available, fall back to blob download."
  []
  (let [scene (:scene @app-state)
        o     (:opts @app-state)]
    (cond
      (nil? scene) nil
      (not (and js/window (.-showSaveFilePicker js/window))) (save-blob!)
      :else
      (let [svg-str (layouts/render-scene scene o)]
        (-> (.showSaveFilePicker
              js/window
              #js {:suggestedName (opts/output-filename (:seed o))
                   :types #js [#js {:description "SVG"
                                    :accept #js {"image/svg+xml" #js [".svg"]}}]})
            (.then (fn [handle]
                     (-> (.createWritable handle)
                         (.then (fn [w]
                                  (-> (.write w svg-str)
                                      (.then #(.close w))
                                      (.then #(show-toast! "saved"))))))))
            (.catch (fn [err]
                      (when-not (= (.-name err) "AbortError")
                        (js/console.warn "save-via-picker failed, falling back" err)
                        (save-blob!)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pan / zoom on the viewport
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- apply-view! []
  (let [{:keys [scale tx ty]} (:view @app-state)]
    (set! (.. (el "canvas") -style -transform)
          (str "translate(" tx "px," ty "px) scale(" scale ")"))))

(defn- reset-view! []
  (swap! app-state assoc :view {:scale 1.0 :tx 0.0 :ty 0.0})
  (apply-view!))

(defn- on-wheel [e]
  (.preventDefault e)
  (let [vp     (el "viewport")
        rect   (.getBoundingClientRect vp)
        x      (- (.-clientX e) (.-left rect))
        y      (- (.-clientY e) (.-top rect))
        {:keys [scale tx ty]} (:view @app-state)
        factor (js/Math.exp (* -0.002 (.-deltaY e)))
        next-s (min 40 (max 0.1 (* scale factor)))
        ratio  (/ next-s scale)]
    (swap! app-state assoc :view {:scale next-s
                                  :tx    (- x (* (- x tx) ratio))
                                  :ty    (- y (* (- y ty) ratio))})
    (apply-view!)))

(defn- on-pointer-down [e]
  (when (zero? (.-button e))
    (let [{:keys [tx ty]} (:view @app-state)]
      (swap! app-state assoc :drag {:x (.-clientX e) :y (.-clientY e)
                                    :tx tx :ty ty :pointer-id (.-pointerId e)}))
    (.setPointerCapture (el "viewport") (.-pointerId e))
    (.add (.-classList (el "viewport")) "panning")))

(defn- on-pointer-move [e]
  (when-let [{:keys [x y tx ty]} (:drag @app-state)]
    (swap! app-state update :view
           assoc
           :tx (+ tx (- (.-clientX e) x))
           :ty (+ ty (- (.-clientY e) y)))
    (apply-view!)))

(defn- on-pointer-up [_]
  (when-let [{:keys [pointer-id]} (:drag @app-state)]
    (try (.releasePointerCapture (el "viewport") pointer-id) (catch :default _))
    (swap! app-state assoc :drag nil)
    (.remove (.-classList (el "viewport")) "panning")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Variation visibility
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sync-variation-visibility! []
  (let [mode       (current-mode)
        variation? (= "triptych-variation" mode)
        qr?        (= "qr" mode)]
    (set! (.-hidden (el "variation-params"))   (not variation?))
    (set! (.-hidden (el "coordinated-params")) (not variation?))
    (set! (.-hidden (el "qr-params"))          (not qr?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; View tabs and inspiration gallery
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare load-inspiration!)

(defn- set-view! [name]
  (swap! app-state assoc :view-name name)
  (doseq [[k id] {"generate" "generate-view" "inspiration" "inspiration-view"}]
    (set! (.-hidden (el id)) (not= k name)))
  (doseq [b (array-seq (.querySelectorAll js/document ".view-tabs button"))]
    (if (= (.. b -dataset -view) name)
      (.add (.-classList b) "active")
      (.remove (.-classList b) "active")))
  (when (and (= name "inspiration") (nil? (:inspiration @app-state)))
    (load-inspiration!)))

(declare open-fullscreen!)

(defn- render-gallery! [images]
  (let [g (el "gallery")]
    (set! (.-innerHTML g) "")
    (doseq [[i nm] (map-indexed vector images)]
      (let [tile (.createElement js/document "div")
            img  (.createElement js/document "img")]
        (set! (.-className tile) "tile")
        (set! (.-src img) (str "/inspiration/" (js/encodeURIComponent nm)))
        (set! (.-alt img) nm)
        (set! (.-loading img) "lazy")
        (.appendChild tile img)
        (.addEventListener tile "click" (fn [_] (open-fullscreen! i)))
        (.appendChild g tile)))))

(defn- load-inspiration! []
  (-> (js/fetch "/inspiration.json")
      (.then (fn [r]
               (if (.-ok r)
                 (.json r)
                 (throw (js/Error. (str "status " (.-status r)))))))
      (.then (fn [data]
               (let [images (vec (array-seq (.-images data)))]
                 (swap! app-state assoc :inspiration images)
                 (render-gallery! images))))
      (.catch (fn [err]
                (js/console.warn "inspiration load failed" err)
                (show-toast! "inspiration load failed")))))

(defn- close-fullscreen! []
  (set! (.-hidden (el "fullscreen")) true)
  (swap! app-state assoc :fs-index -1))

(defn- open-fullscreen! [i]
  (let [images (:inspiration @app-state)]
    (when (and images (pos? (count images)))
      (let [n   (count images)
            idx (mod (+ (mod i n) n) n)
            nm  (nth images idx)]
        (swap! app-state assoc :fs-index idx)
        (set! (.-src (el "fullscreen-img"))
              (str "/inspiration/" (js/encodeURIComponent nm)))
        (set! (.-textContent (el "fullscreen-caption"))
              (str nm "  ·  " (inc idx) "/" n))
        (set! (.-hidden (el "fullscreen")) false)))))

(defn- step-fullscreen! [delta]
  (when (not= -1 (:fs-index @app-state))
    (open-fullscreen! (+ (:fs-index @app-state) delta))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debounced input change → regenerate with same seed
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- debounced-regenerate! []
  (when-let [id (:debounce-id @app-state)] (js/clearTimeout id))
  (swap! app-state assoc :debounce-id
         (js/setTimeout #(do (swap! app-state assoc :debounce-id nil)
                             (generate! {:reuse-seed? true}))
                        120)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyboard shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- on-key-down [e]
  (let [tag (.. e -target -tagName)
        fs-open? (not (.-hidden (el "fullscreen")))
        view-name (:view-name @app-state)]
    (cond
      (or (= tag "INPUT") (= tag "TEXTAREA")) nil
      (or (.-metaKey e) (.-ctrlKey e) (.-altKey e)) nil
      fs-open?
      (case (.-key e)
        "Escape"     (do (.preventDefault e) (close-fullscreen!))
        "ArrowLeft"  (do (.preventDefault e) (step-fullscreen! -1))
        "ArrowRight" (do (.preventDefault e) (step-fullscreen! 1))
        nil)
      (= "generate" view-name)
      (case (.-key e)
        "Enter"       (do (.preventDefault e) (generate!))
        ("s" "S")     (do (.preventDefault e) (save!))
        ("r" "R" "0") (do (.preventDefault e) (reset-view!))
        nil)
      (= "inspiration" view-name)
      (case (.-key e)
        "ArrowLeft"  (do (.preventDefault e) (step-fullscreen! -1))
        "ArrowRight" (do (.preventDefault e) (step-fullscreen! 1))
        nil)
      :else nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Wiring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- replay-animation!
  "Re-run the reveal on the current scene with fresh animation settings
  from the form. Does not regenerate (same seed, same panes)."
  []
  (when-let [scene (:scene @app-state)]
    (let [fresh (current-opts)
          o     (merge (:opts @app-state)
                       (select-keys fresh [:animate :reveal-step-ms]))]
      (swap! app-state assoc :opts o)
      (animate-reveal! scene o))))

(defn- bind-inputs! []
  (doseq [[k id] (concat number-inputs text-inputs checkbox-inputs)]
    (let [e        (el id)
          handler  (if (display-only-keys k)
                     (fn [_] (replay-animation!))
                     debounced-regenerate!)]
      (.addEventListener e "change" handler)
      (.addEventListener e "input"  handler))))

(defn- bind-mode-radios! []
  (doseq [r (array-seq (.querySelectorAll js/document "input[name=mode]"))]
    (.addEventListener r "change"
                       (fn [_]
                         (sync-variation-visibility!)
                         (generate! {:reuse-seed? true})))))

(defn- bind-palette! []
  (.addEventListener (el "palette") "change"
                     (fn [_] (generate! {:reuse-seed? true}))))

(defn- bind-buttons! []
  (.addEventListener (el "generate") "click" (fn [_] (generate!)))
  (.addEventListener (el "save")     "click" (fn [_] (save!)))
  (.addEventListener (el "reset")    "click" (fn [_] (reset-view!))))

(defn- bind-view-tabs! []
  (doseq [b (array-seq (.querySelectorAll js/document ".view-tabs button"))]
    (.addEventListener b "click" (fn [_] (set-view! (.. b -dataset -view))))))

(defn- bind-viewport! []
  (let [vp (el "viewport")]
    (.addEventListener vp "wheel" on-wheel #js {:passive false})
    (.addEventListener vp "pointerdown" on-pointer-down)
    (.addEventListener vp "pointermove" on-pointer-move)
    (.addEventListener vp "pointerup" on-pointer-up)
    (.addEventListener vp "pointercancel" on-pointer-up)
    (.addEventListener vp "dblclick" (fn [_] (reset-view!)))))

(defn- bind-fullscreen! []
  (let [fs (el "fullscreen")]
    (.addEventListener fs "click"
                       (fn [e]
                         (when (or (identical? (.-target e) fs)
                                   (identical? (.-target e) (el "fullscreen-img")))
                           (close-fullscreen!))))
    (.addEventListener (.querySelector fs ".prev") "click"
                       (fn [e] (.stopPropagation e) (step-fullscreen! -1)))
    (.addEventListener (.querySelector fs ".next") "click"
                       (fn [e] (.stopPropagation e) (step-fullscreen! 1)))))

(defn init []
  (bind-buttons!)
  (bind-mode-radios!)
  (bind-palette!)
  (bind-inputs!)
  (bind-view-tabs!)
  (bind-viewport!)
  (bind-fullscreen!)
  (.addEventListener js/document "keydown" on-key-down)
  (sync-variation-visibility!)
  (generate!))
