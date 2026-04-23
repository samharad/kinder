(ns kinder.svg
  "SVG renderer for a pane (or a triptych of panes). Pure data-in,
  string-out. Shared between CLJ and CLJS: numeric formatting is
  hand-rolled so output is byte-identical across runtimes."
  (:require [kinder.rng :as rng]
            [clojure.walk :as walk]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Portable formatting helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- pow10 [n]
  (loop [p 1 i n]
    (if (zero? i) p (recur (* p 10) (dec i)))))

(defn- pad-zeros [s len]
  (let [n (- len (count s))]
    (if (pos? n)
      (str (apply str (repeat n \0)) s)
      s)))

(defn fmt-fixed
  "Format `x` as a fixed-point decimal string with exactly `digits`
  fractional digits. Half-up rounding (shared helper). Byte-identical
  on JVM and JS."
  [x digits]
  (let [neg?      (neg? x)
        ax        (Math/abs (double x))
        scale     (pow10 digits)
        n         (rng/round (* ax scale))
        int-part  (quot n scale)
        frac-part (rem  n scale)
        frac      (when (pos? digits)
                    (str "." (pad-zeros (str frac-part) digits)))]
    (str (when neg? "-") int-part frac)))

(defn- hex2
  "Lowercase 2-digit hex, zero-padded. Input assumed non-negative ≤ 255."
  [n]
  (let [s #?(:clj  (Integer/toString (int n) 16)
             :cljs (.toString n 16))]
    (pad-zeros s 2)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Color
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- hsb->hex [[h s b]]
  (let [s' (/ s 100.0)
        v  (/ b 100.0)]
    (if (zero? s')
      (let [gray (rng/round (* 255.0 v))]
        (str "#" (hex2 gray) (hex2 gray) (hex2 gray)))
      (let [h'      (mod (/ h 60.0) 6.0)
            c       (* v s')
            x       (* c (- 1.0 (Math/abs (- (mod h' 2.0) 1.0))))
            m       (- v c)
            [r g b] (cond
                      (< h' 1) [c x 0]
                      (< h' 2) [x c 0]
                      (< h' 3) [0 c x]
                      (< h' 4) [0 x c]
                      (< h' 5) [x 0 c]
                      :else    [c 0 x])]
        (str "#"
             (hex2 (rng/round (* 255.0 (+ r m))))
             (hex2 (rng/round (* 255.0 (+ g m))))
             (hex2 (rng/round (* 255.0 (+ b m)))))))))

(defn- leaf-rects [rect]
  (if (seq (:children rect))
    (mapcat leaf-rects (:children rect))
    [rect]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Elements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private child-bearer-fill "#000000")

(defn- stroke-attrs [sw]
  (if (pos? sw)
    (str " stroke=\"#000\" stroke-width=\"" (fmt-fixed sw 2) "\"")
    ""))

(defn- rect-el [{:keys [dim loc color children radius]} unit sw]
  (let [[w h] dim
        [x y] loc
        rx    (or radius 2)
        fill  (if (not-empty children) child-bearer-fill (hsb->hex color))]
    (str "<rect x=\"" (fmt-fixed (* x unit) 2)
         "\" y=\"" (fmt-fixed (* y unit) 2)
         "\" width=\"" (fmt-fixed (* w unit) 2)
         "\" height=\"" (fmt-fixed (* h unit) 2)
         "\" rx=\"" (fmt-fixed rx 0)
         "\" fill=\"" fill
         "\"" (stroke-attrs sw) "/>")))

(defn- circle-el [{:keys [loc rad color]} unit sw]
  (let [[cx cy] loc]
    (str "<circle cx=\"" (fmt-fixed (* cx unit) 2)
         "\" cy=\"" (fmt-fixed (* cy unit) 2)
         "\" r=\"" (fmt-fixed (/ (* rad unit) 2.0) 2)
         "\" fill=\"" (hsb->hex color)
         "\"" (stroke-attrs sw) "/>")))

(defn- curve-el
  "Dev overlay: renders :curve-points as a faint dashed polyline so the
  coordinated-circles curve is visible. Returns nil when points is empty
  or missing."
  [points unit sw]
  (when (seq points)
    (let [pts (str/join " "
                (for [[x y] points]
                  (str (fmt-fixed (* x unit) 2) "," (fmt-fixed (* y unit) 2))))]
      (str "<polyline points=\"" pts
           "\" fill=\"none\" stroke=\"#888\" stroke-width=\"" (fmt-fixed (* 0.5 sw) 2)
           "\" stroke-dasharray=\"" (fmt-fixed (* 2.0 sw) 2) " " (fmt-fixed (* 2.0 sw) 2)
           "\" stroke-opacity=\"0.7\"/>"))))

(defn- pane-els
  "Returns a seq of SVG element strings for a single pane. Rects and circles
  are clipped to the pane's bounds so edge circles don't escape into adjacent
  space. The border is drawn on top, unclipped. clip-id must be unique within
  the enclosing document.

  If the pane has :curve-points, a faint dashed polyline is drawn behind
  the circles as a dev overlay."
  [pane unit sw clip-id]
  (let [rect-els (atom [])
        _        (walk/prewalk
                   (fn [node]
                     (when (and (map? node) (:dim node))
                       (swap! rect-els conj (rect-el node unit sw)))
                     node)
                   (:rect pane))
        circle-els (map #(circle-el % unit sw) (:circles pane))
        curve      (curve-el (:curve-points pane) unit sw)
        [w h]    (:dim pane)
        pw       (* w unit)
        ph       (* h unit)
        clip     (str "<clipPath id=\"" clip-id
                      "\"><rect x=\"0\" y=\"0\" width=\"" (fmt-fixed pw 2)
                      "\" height=\"" (fmt-fixed ph 2) "\"/></clipPath>")
        border   (when (pos? sw)
                   (str "<rect x=\"0\" y=\"0\" width=\"" (fmt-fixed pw 2)
                        "\" height=\"" (fmt-fixed ph 2)
                        "\" fill=\"none\" stroke=\"#000\" stroke-width=\""
                        (fmt-fixed sw 2) "\"/>"))]
    (concat
      [clip
       (str "<g clip-path=\"url(#" clip-id ")\">")]
      @rect-els
      (when curve [curve])
      circle-els
      ["</g>"]
      (when border [border]))))

(defn- svg-doc [width height body-lines]
  (str/join "\n"
    (concat
      ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""
            (fmt-fixed width 0) "\" height=\"" (fmt-fixed height 0)
            "\" viewBox=\"0 0 " (fmt-fixed width 2) " " (fmt-fixed height 2) "\">")]
      body-lines
      ["</svg>"])))

(defn render
  "Returns an SVG string for a single fully-rendered pane."
  [pane unit stroke-weight-units]
  (let [sw     (* stroke-weight-units unit)
        [w h]  (:dim pane)
        width  (+ (* w unit) (* 2 sw))
        height (+ (* h unit) (* 2 sw))]
    (svg-doc width height
      (concat
        [(str "<g transform=\"translate(" (fmt-fixed sw 2) "," (fmt-fixed sw 2) ")\">")]
        (pane-els pane unit sw "pane")
        ["</g>"]))))

(defn render-qr
  "Render a QR pane from its subdivision tree, including black lead
  lines around each leaf rect."
  [pane unit stroke-weight-units]
  (render pane unit stroke-weight-units))

(defn render-triptych
  "Returns an SVG string with three panes in a configurable width ratio,
  separated by gap-units of whitespace."
  [left center right unit stroke-weight-units gap-units]
  (let [sw       (* stroke-weight-units unit)
        gap      (* gap-units unit)
        [lw h]   (:dim left)
        [cw _]   (:dim center)
        total-w  (+ (* lw unit) gap (* cw unit) gap (* lw unit) (* 2 sw))
        total-h  (+ (* h unit) (* 2 sw))
        left-x   sw
        center-x (+ (* lw unit) gap sw)
        right-x  (+ (* lw unit) gap (* cw unit) gap sw)]
    (svg-doc total-w total-h
      (concat
        [(str "<g transform=\"translate(" (fmt-fixed left-x 2) "," (fmt-fixed sw 2) ")\">")]
        (pane-els left unit sw "pane-left")
        ["</g>"
         (str "<g transform=\"translate(" (fmt-fixed center-x 2) "," (fmt-fixed sw 2) ")\">")]
        (pane-els center unit sw "pane-center")
        ["</g>"
         (str "<g transform=\"translate(" (fmt-fixed right-x 2) "," (fmt-fixed sw 2) ")\">")]
        (pane-els right unit sw "pane-right")
        ["</g>"]))))
