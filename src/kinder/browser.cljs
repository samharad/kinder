(ns kinder.browser
  "Browser-local kinder runtime. Reads controls, generates panes and SVG
  locally using the shared CLJC generator and renderer, injects the
  result into the DOM, and handles progressive reveal, save, share URL
  sync, pan/zoom, and the inspiration gallery."
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
(defn- set-val! [id v] (set! (.-value (el id)) (str v)))
(defn- set-checked! [id checked] (set! (.-checked (el id)) (boolean checked)))

(defn- current-mode []
  (or (some (fn [n] (when (.-checked n) (.-value n)))
            (array-seq (.querySelectorAll js/document "input[name=mode]")))
      "triptych-variation"))

(defn- set-mode! [mode]
  (doseq [n (array-seq (.querySelectorAll js/document "input[name=mode]"))]
    (set! (.-checked n) (= (.-value n) mode))))

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

(defn- apply-opts-to-form! [o]
  (doseq [[k id] number-inputs]
    (set-val! id (get o k)))
  (doseq [[k id] text-inputs]
    (set-val! id (get o k)))
  (doseq [[k id] checkbox-inputs]
    (set-checked! id (get o k)))
  (set-mode! (:mode o))
  (set-val! "palette" (:palette o)))

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

(defn- local-form-opts []
  (select-keys (or (:opts @app-state) (current-opts))
               display-only-keys))

(defn- current-share-url [o]
  (let [loc    (.-location js/window)
        params (js/URLSearchParams.)]
    (doseq [[k v] (opts/share-param-pairs o)]
      (.set params k v))
    (str (.-origin loc)
         (.-pathname loc)
         "?"
         (.toString params)
         (.-hash loc))))

(defn- sync-share-url! [o history-mode]
  (let [url   (current-share-url o)
        state #js {:seed (:seed o)}]
    (case history-mode
      :push    (.pushState (.-history js/window) state "" url)
      :replace (.replaceState (.-history js/window) state "" url)
      nil)))

(defn- read-location-query []
  (let [params (js/URLSearchParams. (.-search (.-location js/window)))
        query  (atom {})]
    (.forEach params (fn [v k] (swap! query assoc k v)))
    @query))

(defn- shared-opts-from-location []
  (let [recognized (opts/share-query-input (read-location-query))]
    (when (seq recognized)
      (select-keys (opts/normalize recognized)
                   opts/share-keys))))

(defn- shared-opts-for-form []
  (when-let [shared (shared-opts-from-location)]
    (merge (current-opts)
           (local-form-opts)
           shared)))

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
  ([{:keys [reuse-seed? history-mode opts]}]
   (let [o       (or opts (current-opts))
         prior   (:opts @app-state)
         seed    (cond
                   (:seed o) (:seed o)
                   (and reuse-seed? (:seed prior)) (:seed prior)
                   :else (rng/ambient-seed))
         o       (assoc o :seed seed)
         scene   (layouts/generate-scene o)]
     (swap! app-state assoc :scene scene :opts o)
     (sync-share-url! o (or history-mode (if reuse-seed? :replace :push)))
     (update-seed-display! seed (:mode o))
     (animate-reveal! scene o))))

(def ^:private svg-save-types
  #js [#js {:description "SVG"
            :accept #js {"image/svg+xml" #js [".svg"]}}])

(defn- save-payloads []
  (when-let [scene (:scene @app-state)]
    (let [o               (:opts @app-state)
          share-url       (current-share-url o)
          filename-base   (opts/output-filename-base (:seed o))
          artwork-name    (opts/svg-filename filename-base)
          qr-name         (opts/svg-filename filename-base "-QR-code")
          qr-opts         (assoc o
                                 :mode "qr"
                                 :palette "kinder"
                                 :text share-url
                                 :seed (str (:seed o) "/save-qr"))
          qr-scene        (layouts/generate-scene qr-opts)
          artwork-svg-str (layouts/render-scene scene o)
          qr-svg-str      (layouts/render-scene qr-scene qr-opts)]
      [{:filename artwork-name :svg-str artwork-svg-str}
       {:filename qr-name :svg-str qr-svg-str}])))

(defn- download-svg! [{:keys [filename svg-str]}]
  (let [blob (js/Blob. #js [svg-str] #js {:type "image/svg+xml"})
        url  (js/URL.createObjectURL blob)
        a    (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.. js/document -body (appendChild a))
    (.click a)
    (.. js/document -body (removeChild a))
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))

(defn- save-blob! [payloads]
  (doseq [payload payloads]
    (download-svg! payload))
  (let [[artwork qr] payloads]
    (show-toast! (str "saved " (:filename artwork) " + " (:filename qr)))))

(defn- save-via-picker! [{:keys [filename svg-str]}]
  (-> (.showSaveFilePicker
        js/window
        #js {:suggestedName filename
             :types         svg-save-types})
      (.then (fn [handle]
               (-> (.createWritable handle)
                   (.then (fn [w]
                            (-> (.write w svg-str)
                                (.then #(.close w))))))))))

(defn save!
  "Prefer the native save dialog when available, fall back to blob download."
  []
  (when-let [payloads (save-payloads)]
    (cond
      (not (and js/window (.-showSaveFilePicker js/window)))
      (save-blob! payloads)

      :else
      (let [[artwork qr] payloads
            artwork-saved? (atom false)]
        (-> (save-via-picker! artwork)
            (.then (fn [_]
                     (reset! artwork-saved? true)
                     (save-via-picker! qr)))
            (.then (fn [_]
                     (show-toast! (str "saved " (:filename artwork) " + " (:filename qr)))))
            (.catch (fn [err]
                      (cond
                        (= (.-name err) "AbortError")
                        (show-toast! (if @artwork-saved?
                                       (str "saved " (:filename artwork) "; QR save cancelled")
                                       "save cancelled"))

                        @artwork-saved?
                        (do
                          (js/console.warn "companion QR save failed" err)
                          (show-toast! (str "saved " (:filename artwork) "; QR save failed")))

                        :else
                        (do
                          (js/console.warn "save-via-picker failed, falling back" err)
                          (save-blob! payloads))))))))))

(defn copy-share-link! []
  (when-let [o (:opts @app-state)]
    (let [url (current-share-url o)]
      (if-let [clipboard (.. js/navigator -clipboard)]
        (-> (.writeText clipboard url)
            (.then (fn [] (show-toast! "link copied")))
            (.catch (fn [err]
                      (js/console.warn "clipboard write failed" err)
                      (show-toast! "copy failed"))))
        (show-toast! "clipboard unavailable")))))

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
        ("l" "L")     (do (.preventDefault e) (copy-share-link!))
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
  (.addEventListener (el "copy-link") "click" (fn [_] (copy-share-link!)))
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
  (.addEventListener js/window "popstate"
                     (fn [_]
                       (when-let [shared (shared-opts-for-form)]
                         (apply-opts-to-form! shared)
                         (sync-variation-visibility!)
                         (generate! {:opts shared :history-mode :replace}))))
  (if-let [shared (shared-opts-for-form)]
    (do
      (apply-opts-to-form! shared)
      (sync-variation-visibility!)
      (generate! {:opts shared :history-mode :replace}))
    (do
      (sync-variation-visibility!)
      (generate! {:history-mode :replace}))))
