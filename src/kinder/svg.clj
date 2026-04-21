(ns kinder.svg
  (:require [kinder.core :as core]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(defn- hsb->hex [[h s b]]
  (let [s' (/ s 100.0)
        v  (/ b 100.0)]
    (if (zero? s')
      (let [gray (Math/round (* 255.0 v))]
        (format "#%02x%02x%02x" gray gray gray))
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
        (format "#%02x%02x%02x"
                (Math/round (* 255.0 (+ r m)))
                (Math/round (* 255.0 (+ g m)))
                (Math/round (* 255.0 (+ b m))))))))

(def ^:private neon-pink (hsb->hex [315 100 100]))

(defn- rect-el [{:keys [dim loc color children radius]} unit sw]
  (let [[w h] dim
        [x y] loc
        rx    (or radius 2)
        fill  (if (not-empty children) neon-pink (hsb->hex color))]
    (format "<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" rx=\"%.0f\" fill=\"%s\" stroke=\"#000\" stroke-width=\"%.2f\"/>"
            (float (* x unit)) (float (* y unit))
            (float (* w unit)) (float (* h unit))
            (float rx)
            fill
            (float sw))))

(defn- circle-el [{:keys [loc rad color]} unit sw]
  (let [[cx cy] loc]
    (format "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\" stroke=\"#000\" stroke-width=\"%.2f\"/>"
            (float (* cx unit))
            (float (* cy unit))
            (float (/ (* rad unit) 2.0))
            (hsb->hex color)
            (float sw))))

(defn- pane-els
  "Returns a seq of SVG element strings for a single pane. Rects and circles
  are clipped to the pane's bounds so edge circles don't escape into adjacent
  space. The border is drawn on top, unclipped. clip-id must be unique within
  the enclosing document."
  [pane unit sw clip-id]
  (let [rect-els (atom [])
        _        (walk/prewalk
                   (fn [node]
                     (when (and (map? node) (:dim node))
                       (swap! rect-els conj (rect-el node unit sw)))
                     node)
                   (:rect pane))
        circle-els (map #(circle-el % unit sw) (:circles pane))
        [w h]    (:dim pane)
        pw       (float (* w unit))
        ph       (float (* h unit))
        clip     (format "<clipPath id=\"%s\"><rect x=\"0\" y=\"0\" width=\"%.2f\" height=\"%.2f\"/></clipPath>"
                         clip-id pw ph)
        border   (format "<rect x=\"0\" y=\"0\" width=\"%.2f\" height=\"%.2f\" fill=\"none\" stroke=\"#000\" stroke-width=\"%.2f\"/>"
                         pw ph (float sw))]
    (concat
      [clip
       (format "<g clip-path=\"url(#%s)\">" clip-id)]
      @rect-els
      circle-els
      ["</g>" border])))

(defn- svg-doc [width height body-lines]
  (str/join "\n"
    (concat
      ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       (format "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\">" width height)]
      body-lines
      ["</svg>"])))

(defn render
  "Returns an SVG string for a single fully-rendered pane."
  [pane unit stroke-weight-units]
  (let [sw     (* stroke-weight-units unit)
        sw2    (/ sw 2.0)
        [w h]  (:dim pane)
        width  (+ (* w unit) sw)
        height (+ (* h unit) sw)]
    (svg-doc width height
      (concat
        [(format "<g transform=\"translate(%.2f,%.2f)\">" sw2 sw2)]
        (pane-els pane unit sw "pane")
        ["</g>"]))))

(defn render-triptych
  "Returns an SVG string with three panes in a 1:2:1 width ratio,
  separated by gap-units of whitespace. left and right should have
  equal dimensions; center should be twice the width."
  [left center right unit stroke-weight-units gap-units]
  (let [sw      (* stroke-weight-units unit)
        sw2     (/ sw 2.0)
        gap     (* gap-units unit)
        [lw h]  (:dim left)
        [cw _]  (:dim center)
        total-w (+ (* lw unit) gap (* cw unit) gap (* lw unit) sw)
        total-h (+ (* h unit) sw)
        left-x   sw2
        center-x (+ (* lw unit) gap sw2)
        right-x  (+ (* lw unit) gap (* cw unit) gap sw2)]
    (svg-doc total-w total-h
      (concat
        [(format "<g transform=\"translate(%.2f,%.2f)\">" left-x sw2)]
        (pane-els left unit sw "pane-left")
        ["</g>"
         (format "<g transform=\"translate(%.2f,%.2f)\">" center-x sw2)]
        (pane-els center unit sw "pane-center")
        ["</g>"
         (format "<g transform=\"translate(%.2f,%.2f)\">" right-x sw2)]
        (pane-els right unit sw "pane-right")
        ["</g>"]))))
