(ns kinder.qr
  "QR encoding plus a Kinder-compatible subdivision tree builder.

  The final QR is produced from a legal rectangle-division tree: a root
  rect gets recursively split on module boundaries until every leaf
  covers a uniform QR region. There are no arbitrary overlay blocks."
  (:require [clojure.string :as str]
            [kinder.rng :as rng]
            ["qrcode-generator" :as qrcode]))

(def error-levels #{"L" "M" "Q" "H"})

(defn- normalize-ecl [ecl]
  (let [s (some-> ecl name str/upper-case)]
    (if (contains? error-levels s) s "H")))

(defn- build-matrix [qr size]
  (mapv (fn [r]
          (mapv (fn [c] (boolean (.isDark ^js qr r c)))
                (range size)))
        (range size)))

(defn encode
  "Encode `text` as a QR module matrix."
  [text & [ecl]]
  (let [ecl' (normalize-ecl ecl)
        qr   (qrcode 0 ecl')
        _    (.addData qr (or text ""))
        _    (.make qr)
        size (.getModuleCount qr)]
    {:size    size
     :modules (build-matrix qr size)
     :ecl     ecl'
     :text    (or text "")}))

(defn- weighted-selection [rng pairs]
  (let [total (reduce + (map second pairs))
        r     (rng/rand-int rng total)]
    (reduce (fn [acc [v n]]
              (let [acc' (+ acc n)]
                (if (< r acc')
                  (reduced v)
                  acc')))
            0
            pairs)))

(defn- weighted-dark-accents [palette]
  (let [accents (:accent palette)]
    (if (seq accents)
      (mapv (fn [acc] [acc 1]) accents)
      [[[0 0 0] 1]])))

(defn- pad-modules [modules quiet-zone]
  (let [size   (count modules)
        row-w  (+ size (* 2 quiet-zone))
        border (vec (repeat row-w false))
        padrow (fn [row]
                 (vec (concat (repeat quiet-zone false)
                              row
                              (repeat quiet-zone false))))]
    (vec (concat (repeat quiet-zone border)
                 (map padrow modules)
                 (repeat quiet-zone border)))))

(defn- uniform-region? [grid x y w h]
  (let [v (nth (nth grid y) x)]
    (every? (fn [row]
              (every? #(= v %) (subvec row x (+ x w))))
            (subvec grid y (+ y h)))))

(defn- region-dark? [grid x y]
  (nth (nth grid y) x))

(defn- center-weight [n cut]
  (let [dist (Math/abs (- (* 2 cut) n))]
    (max 1 (- (* 2 n) dist))))

(defn- cut-option [grid axis x y w h cut]
  (let [[aw ah bw bh] (if (= axis :vert)
                        [cut h (- w cut) h]
                        [w cut w (- h cut)])
        [bx by]       (if (= axis :vert)
                        [(+ x cut) y]
                        [x (+ y cut)])
        side-bonus    (+ (if (uniform-region? grid x y aw ah) 12 0)
                         (if (uniform-region? grid bx by bw bh) 12 0))
        axis-bonus    (cond
                         (> h w) (if (= axis :horz) 3 1)
                         (> w h) (if (= axis :vert) 3 1)
                         :else 2)]
    {:axis   axis
     :cut    cut
     :weight (* axis-bonus (+ side-bonus (center-weight (if (= axis :vert) w h) cut)))}))

(defn- split-options [grid x y w h]
  (vec
    (concat
      (when (> w 1)
        (map #(cut-option grid :vert x y w h %)
             (range 1 w)))
      (when (> h 1)
        (map #(cut-option grid :horz x y w h %)
             (range 1 h))))))

(defn- split-rect [rect axis cut]
  (let [{:keys [dim loc id]} rect
        [w h] dim
        [x y] loc]
    (if (= axis :vert)
      [(assoc rect
              :dim [cut h]
              :id (str id "0"))
       (assoc rect
              :dim [(- w cut) h]
              :loc [(+ x cut) y]
              :id (str id "1"))]
      [(assoc rect
              :dim [w cut]
              :id (str id "0"))
       (assoc rect
              :dim [w (- h cut)]
              :loc [x (+ y cut)]
              :id (str id "1"))])))

(declare build-tree*)

(defn- build-tree* [rng grid dark-pairs light-color radius rect]
  (let [{[w h] :dim [x y] :loc} rect]
    (if (uniform-region? grid x y w h)
      (assoc rect
             :color (if (region-dark? grid x y)
                      (weighted-selection rng dark-pairs)
                      light-color)
             :radius radius
             :children [])
      (let [{:keys [axis cut]} (weighted-selection rng
                                                   (mapv (fn [{:keys [weight] :as option}]
                                                           [option weight])
                                                         (split-options grid x y w h)))
            [a b]             (split-rect rect axis cut)]
        (assoc rect
               :color light-color
               :radius radius
               :children [(build-tree* rng grid dark-pairs light-color radius a)
                          (build-tree* rng grid dark-pairs light-color radius b)])))))

(defn generate-pane
  "Return a pane-shaped QR scene whose rectangles all come from legal
  recursive splits."
  [qr & {:keys [palette quiet-zone seed corner-radius]
         :or   {quiet-zone 4
                corner-radius 2.0}}]
  (let [grid       (pad-modules (:modules qr) quiet-zone)
        size       (count grid)
        rng        (rng/make-rng (or seed "qr"))
        light      (:main palette)
        dark-pairs (weighted-dark-accents palette)
        root       {:dim [size size]
                    :loc [0 0]
                    :radius corner-radius
                    :id  ""}]
    {:rect    (build-tree* rng grid dark-pairs light corner-radius root)
     :circles []
     :seed    seed
     :dim     [size size]
     :qr      qr}))
