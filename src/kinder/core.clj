(ns kinder.core
  "Mostly-pure functions that output and manipulate data representing
  a generative artwork image.

  The current approach to pseudo-randomness is to use the `random-seed`
  library, which introduces a `set-random-seed!` function and rand-x
  functions which use that seed. A better approach, in the future, might
  be to explicitly pass around an RNG.

  Ignorant of Quil and state.
  "
  (:refer-clojure :exclude [rand rand-int rand-nth])
  (:require [random-seed.core :refer [rand rand-int rand-nth set-random-seed!]]
            [clojure.spec.alpha :as s]
            [orchestra.spec.test :as st])
  (:import (java.util Random)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Spec
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::color (s/coll-of int? :kind vector? :count 3))
(s/def ::main ::color)
(s/def ::accent (s/coll-of ::color))
(s/def ::palette (s/keys :req-un [::main ::accent]))

(s/def ::seed integer?)

(s/def ::radius number?)
;; TODO: fails if "pos-int?" !!!!!
(s/def ::dim (s/coll-of number? :kind vector? :count 2))
(s/def ::loc (s/coll-of number? :kind vector? :count 2))
(s/def ::assigned-color ::color)
(s/def ::children (s/coll-of ::rect))
(s/def ::rect (s/keys :req-un [::dim ::loc ::color ::assigned-color]
                      :opt-un [::children]))

(s/def ::rad pos-int?)
(s/def ::circle (s/keys :req-un [::loc ::rad ::color]))
(s/def ::circles (s/coll-of ::circle))

(s/def ::pane (s/keys :req-un [::rect ::seed ::dim ::circles]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn divisors [n]
  (->> (range 1 (inc n))
       (filter #(zero? (rem n %)))))

(defn weighted-selection [pairs]
  (let [total (reduce + (map second pairs))
        r (rand-int total)]
    (reduce (fn [acc [v n]]
              (let [acc' (+ acc n)]
                (if (< r acc')
                  (reduced v)
                  acc')))
            0
            pairs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def kinder-palette {:main [57, 8, 93]
                     :accent [[354, 99, 64]
                              [121, 41, 57]
                              [215, 99, 45]
                              [35, 77, 91]]})
                              ;[0, 100, 0]]})

(def red-palette {:main [57, 8, 93]
                  :accent [[354, 99, 64]]})

(def orange-palette {:main [46 27 85]
                     :accent [[32 94 99]
                              [174 15 25]
                              [150 5 17]
                              [204 5 78]]})

(def palettes [kinder-palette
               #_red-palette
               #_orange-palette])

(def ^:dynamic palette kinder-palette)
(def ^:dynamic seed-rect {:dim [0 0]})

(defn- main-color []
  (:main palette))

(defn- some-accent-color []
  (rand-nth (:accent palette)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coloring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- assign-some-color [rect parent-color]
  (let [{:keys [dim]} rect
        [w h] dim
        color (cond
                (or (> w 3) (> h 3))
                (weighted-selection [[parent-color 1]
                                     [(some-accent-color) 10]])
                :else
                (weighted-selection [[parent-color 10]
                                     [(some-accent-color) 1]]))]
    (assoc rect :assigned-color color)))

(defn- express-some-color [rect]
  (let [{:keys [dim id assigned-color]} rect
        [w h] dim
        is-large (or (> w 4) (> h 4))
        accent assigned-color
        main (main-color)
        is-square (= w h)
        [id-a id-b] (concat
                      (->> id
                           (take-last 2)
                           (map str)
                           (map #(Integer/parseInt %)))
                      [0 0])
        id-indicates-color (= (mod id-a 2) (mod id-b 2))
        color (cond
                (not is-square) main
                is-large (weighted-selection [[main 10]
                                              [accent 3]])
                id-indicates-color (weighted-selection [[accent 10]
                                                        [main 1]])
                :else (weighted-selection [[main 10]
                                           [accent 1]]))]
    (assoc rect :color color)))

(defn- assign-and-express-color [rect parent-color]
  (express-some-color (assign-some-color rect parent-color)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rect child-bearers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- flip-axes [child-bearer-f]
  (let [reversev (comp vec reverse)
        flip-axes (fn flip-axes [rect] (-> rect
                                           (update :dim reversev)
                                           (update :loc reversev)
                                           (update :children #(if (not-empty %)
                                                                (map flip-axes %)
                                                                %))))]
    (fn [rect]
      (let [children (-> rect flip-axes child-bearer-f)]
        (doall (mapv flip-axes children))))))

(defn- horz-even-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        divs (divisors h)
        div (weighted-selection (mapv #(vector %1 %2)
                                      divs
                                      (range (count divs) 0 -1)))]
    (->> (range y (+ y h) div)
         (map-indexed #(assign-and-express-color {:dim [w div]
                                                  :loc    [x %2]
                                                  :id (str id %1)}
                                                 assigned-color))
         (doall))))
(def vert-even-children (flip-axes horz-even-children))

(defn- horz-sym-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        h' (inc (rand-int (int (/ h 3))))
        y'a y
        y'b (- (+ y h) h')
        r {:dim [w h']}]
    (->> [(assoc r :loc [x y'a])
          {:loc [x (+ y'a h')] :dim [w (- h h' h')]}
          (assoc r :loc [x y'b])]
         (map-indexed #(assoc %2 :id (str id %1)))
         (mapv #(assign-and-express-color % assigned-color))
         (doall))))

(def vert-sym-children (flip-axes horz-sym-children))

(defn- horz-stripe-sem-children
  "Bastard function -- renders not one but two 'levels' of children!
  Furthermore, it breaks some squares down to half-unit!

  Asserts that the rect is 1 unit wide!
  "
  [rect]
  {:pre [(= 1 (first (:dim rect)))]}
  (let [odd-or-even (rand-int 2)
        children (->> (horz-even-children rect)
                      (map (fn more-children [rect]
                             (let [{:keys [id dim loc assigned-color]} rect
                                   [w h] dim
                                   [x y] loc
                                   should-probably-split (= odd-or-even (mod (Integer/parseInt (str (last id)))
                                                                             2))
                                   should-split (or (and should-probably-split (not= 0 (rand-int 4)))
                                                    (= 0 (rand-int 10)))
                                   children (if should-split
                                              (mapv #(assign-and-express-color % assigned-color)
                                                    [{:dim [(* 0.5 w) h]
                                                      :loc [x y]
                                                      :id (str id "0")}
                                                     {:dim [(* 0.5 w) h]
                                                      :loc [(+ x (* 0.5 w)) y]
                                                      :id (str id "1")}])
                                              [])]
                               (assoc rect :children children)))))]
    children))
(def vert-stripe-sem-children (flip-axes horz-stripe-sem-children))

(defn- horz-rand-children [rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        num-kids (+ 2 (rand-int 4))
        ranges (->> (repeatedly #(rand-nth (map inc (range y (+ y h)))))
                    (take num-kids)
                    (doall)
                    (sort)
                    (cons y)
                    (vec))
        ranges (->> (conj ranges (+ y h))
                    (partition 2 1)
                    (map vec))
        rs (doall
             (map-indexed (fn [i [ya yb]]
                            (assign-and-express-color {:dim [w (- yb ya)]
                                                       :loc    [x ya]
                                                       :id (str id i)}
                                                      assigned-color))
                          ranges))]
    rs))
(def vert-rand-children (flip-axes horz-rand-children))

(defn- children [sym rand even stripe-sem]
  (fn [rect]
    (let [{:keys [dim]} rect
          [w h] dim
          seed-dim (:dim seed-rect)
          [seed-w seed-h] seed-dim
          is-pretty-small (and true
                               ;(<= w 4) (<= h 4)
                               (< w (* 0.15 seed-w))
                               (< h (* 0.15 seed-h)))
          is-pretty-big (and (> w (* 0.5 seed-w))
                             (> h (* 0.5 seed-h)))
          is-very-big (and (> w (* 0.9 seed-w))
                           (> h (* 0.9 seed-h)))

          is-seed-rect (and (= w seed-w)
                            (= h seed-h))

          is-short-and-skinny (or
                                (and (<= w 3) (<= h 10))
                                (and (<= h 3) (<= w 10)))
          skinny 3 ;; TODO tweak me
          is-skinny (or (<= w skinny) (<= h skinny))
          is-maximally-skinny (or (= w 1) (= h 1))

          is-long-and-maximally-skinny (and is-maximally-skinny
                                            (or (> w 10) (> h 10)))
          ;is-short (or (<= w 10) (<= h 10))
          is-square (= w h)

          f (cond
              (and (<= w 1) (<= h 1))
              (constantly [])

              is-seed-rect
              (weighted-selection [[sym 1]])

              (and is-square (> w 6) (> h 6))
              (weighted-selection [[(constantly []) 4]
                                   [sym 1]
                                   [rand 1]
                                   [even 1]])

              is-very-big
              (weighted-selection [[sym 6]
                                   [rand 3]])

              is-pretty-big
              (weighted-selection [[sym 6]
                                   [rand 3]
                                   [(constantly []) 4]])

              is-long-and-maximally-skinny
              (weighted-selection [[even 10]
                                   [rand 10]
                                   [(constantly []) 10]
                                   [stripe-sem 20]])

              is-maximally-skinny
              (weighted-selection [[even 10]
                                   ;[even 10]
                                   ;[rand 10]
                                   [(constantly []) 4]])

              ;is-short-and-skinny
              is-skinny ;; TODO tweak me
              (weighted-selection [[even 10]
                                   ;[even 10]
                                   ;[rand 10]
                                   [(constantly []) 6]])

              is-pretty-small
              (weighted-selection [[even 2]
                                   [rand 1]
                                   [sym 1]
                                   [(constantly []) 10]])

              :else
              (weighted-selection [[sym 4]
                                   [rand 8]
                                   [even 2]
                                   [(constantly []) 10]]))
          children (f rect)]
      (map #(assoc % :radius 2)
           children))))

(def horz-children (children horz-sym-children
                             horz-rand-children
                             horz-even-children
                             horz-stripe-sem-children))

(def vert-children (children vert-sym-children
                             vert-rand-children
                             vert-even-children
                             vert-stripe-sem-children))

(defn- make-direct-children [rect]
  (let [[w h] (:dim rect)
        is-vert (> h w)]
    (cond
      (and (<= w 1) (<= h 1)) (or (:children rect) [])
      (<= w 1) (horz-children rect)
      (<= h 1) (vert-children rect)
      is-vert (weighted-selection [[(horz-children rect) 10]
                                   [(vert-children rect) 1]])
      :else (weighted-selection [[(horz-children rect) 1]
                                 [(vert-children rect) 10]]))))

(defn- with-some-direct-children [rect]
  (let [children (make-direct-children rect)]
    (assoc rect :children children)))


(defn take-rect-depth [depth rect]
  (if (zero? depth)
    (if (:children rect)
      (assoc rect :children [])
      rect)
    (if (:children rect)
      (-> rect
          (update :children (partial map #(take-rect-depth (dec depth) %)))
          (update :children doall))
      rect)))

(defn take-depth [depth pane]
  (let [{:keys [rect circles]} pane
        rect' (take-rect-depth depth rect)
        circles' (if (= rect (take-rect-depth depth rect))
                   circles
                   [])]
    (-> pane
        (assoc :rect rect')
        (assoc :circles circles'))))

(defn with-random-children [rect]
  (-> rect
      (with-some-direct-children)
      (update :children (partial map #(with-random-children %)))
      (update :children doall)))

(defn some-rect [dimensions]
  (let [accent (some-accent-color)
        main (main-color)]
    (-> (with-random-children {:dim            dimensions
                               :radius         0
                               :loc            [0 0]
                               :assigned-color accent
                               :color          main
                               :id             ""
                               :children       []})
        (update :children doall))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- own-corner-coords [rect]
  (let [{:keys [loc dim]} rect
        [w h] dim
        [x y] loc
        corners [[x y]
                 [x (+ y h)]
                 [(+ x w) y]
                 [(+ x w) (+ y h)]]]
    (set corners)))

(defn- all-corner-coords [rect]
  (let [corners (own-corner-coords rect)]
    (reduce into corners (doall (map all-corner-coords (:children rect))))))

(defn dist [[ax ay] [bx by]]
  (Math/sqrt (+ (Math/pow (- bx ax) 2)
                (Math/pow (- by ay) 2))))

;; --- circle fitting ------------------------------------------------------
;;
;; Note on radius semantics: the :rad field on circles is effectively the
;; *diameter* -- svg.clj renders with r = rad * unit / 2. The pre-existing
;; collision predicate `(+ rad1 rad2) >= dist` therefore demands that
;; centers be farther apart than the sum of diameters, i.e. roughly 2x
;; the minimum needed to avoid touching. We preserve that 2x breathing
;; room so the aesthetic doesn't change here.

(def ^:private min-visible-fraction
  "Circles whose visible fraction drops below this get nudged inward (or
  skipped if nudging fails). 0.5 means at least half the disk is in-pane
  -- tiny edge slivers are eliminated while the edge-flirting look is kept."
  0.5)

(defn- visible-fraction
  "Approximate fraction of the disk (center [cx cy], radius r) that falls
  inside rect [0, w] × [0, h]. Estimated with a 16×16 grid sample of the
  disk's bounding square."
  [cx cy r w h]
  (let [n 16
        step (/ (* 2.0 r) (dec n))
        r2   (* r r)]
    (loop [i 0 in-disk 0 in-both 0]
      (if (= i (* n n))
        (if (zero? in-disk) 0.0 (/ (double in-both) in-disk))
        (let [ix (mod i n)
              iy (quot i n)
              x  (+ (- cx r) (* step ix))
              y  (+ (- cy r) (* step iy))
              dx (- x cx)
              dy (- y cy)]
          (if (<= (+ (* dx dx) (* dy dy)) r2)
            (recur (inc i) (inc in-disk)
                   (if (and (<= 0 x w) (<= 0 y h)) (inc in-both) in-both))
            (recur (inc i) in-disk in-both)))))))

(defn- conflicting-circle
  "Returns the first entry in `others` whose rad-sum (current 2x-spacing
  convention) reaches or exceeds the center distance from [cx cy]."
  [cx cy rad others]
  (first (filter (fn [{[ox oy] :loc other-rad :rad}]
                   (<= (dist [cx cy] [ox oy]) (+ rad other-rad)))
                 others)))

(defn- nudge-toward-rect-center [[cx cy] w h amount]
  (let [dx (- (/ w 2.0) cx)
        dy (- (/ h 2.0) cy)
        d  (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? d)
      [cx cy]
      [(+ cx (* amount (/ dx d)))
       (+ cy (* amount (/ dy d)))])))

(defn- nudge-away-from
  "Nudges [cx cy] along the line from `other`'s center outward so the two
  circles sit exactly at the rad-sum distance (plus a tiny margin)."
  [[cx cy] rad [ox oy] other-rad]
  (let [dx     (- cx ox)
        dy     (- cy oy)
        d      (Math/sqrt (+ (* dx dx) (* dy dy)))
        target (* 1.001 (+ rad other-rad))]
    (cond
      (zero? d)       [(+ ox target) oy]       ; centers coincide; arbitrary dir
      (>= d target)   [cx cy]
      :else           [(+ ox (* (/ dx d) target))
                       (+ oy (* (/ dy d) target))])))

(def ^:private fit-max-tries 8)

(defn- fit-circle
  "Attempts to place a circle of `rad` (a diameter, per the rad convention)
  near [cx cy] inside panel [0, w] × [0, h] without overlapping `existing`.
  Iteratively nudges: first inward when visible fraction is below
  min-visible-fraction, then away from any conflicting circle. Returns
  [cx' cy'] on success, nil if the constraints can't be met in a few tries."
  [cx cy rad w h existing]
  (let [r (/ rad 2.0)]
    (loop [x cx y cy tries 0]
      (cond
        (> tries fit-max-tries) nil

        (< (visible-fraction x y r w h) min-visible-fraction)
        (let [amount (max 0.5 (/ r 2.0))
              [x' y'] (nudge-toward-rect-center [x y] w h amount)]
          (if (and (= x' x) (= y' y))
            nil           ; at or past center and still failing -- give up
            (recur x' y' (inc tries))))

        :else
        (if-let [c (conflicting-circle x y rad existing)]
          (let [[x' y'] (nudge-away-from [x y] rad (:loc c) (:rad c))]
            (recur x' y' (inc tries)))
          [x y])))))

(defn some-circle [rect circles]
  (let [rad (rand-nth (range 3 10))
        [w h] (:dim rect)
        all-corners (all-corner-coords rect)
        candidates (map (fn [[x y]]
                          (let [jit #(rand-nth (range -1 2 0.1))]
                            [(+ x (jit)) (+ y (jit))]))
                        all-corners)
        fitted (keep (fn [[x y]] (fit-circle x y rad w h circles)) candidates)]
    (if (empty? fitted)
      []
      [{:rad rad :loc (rand-nth fitted) :color (some-accent-color)}])))


(defn some-circles [rect]
  ;; TODO! Need to be:
  ;;  - Sometimes plain colored
  ;;  - Possibly: in a general cluster
  ;;  - Not on 'phantom' corners (bug)
  ;;  - Jitter-biased to the corner itself?
  (let [num-circles (rand-nth (range 3 5))]
    (loop [circles []
           calls 0]
      (if (= calls num-circles)
        circles
        (recur (into circles
                     (some-circle rect circles))
               (inc calls))))))

(s/fdef generate-pane
  :args (s/cat :dimensions ::dim :opts (s/keys* :opt-un [::seed ::palette]))
  :ret ::pane)
(defn generate-pane [dimensions & {:keys [seed palette]}]
  "Outputs a kinder-symphony work. Demands dimensions in the form
   of [w h] -- we refuse to generate random dimensions.
   Other parameters are optional.
   "
  (let [seed (or seed (-> (Random.) .nextLong))
        _ (set-random-seed! seed)
        ;; Always select a palette, so that seed is deterministic
        default-palette (rand-nth palettes)
        palette (or palette default-palette)]
    (binding [palette palette
              seed-rect {:dim dimensions :radius 0}]
      (let [rect (some-rect dimensions)
            circles (some-circles rect)]
        {:rect    rect
         :circles circles
         :seed    seed
         :dim     dimensions}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation (for triptych-variation)
;;
;; Mutating a pane means picking N random subtrees and regenerating each,
;; keeping the rest of the tree intact. This produces panels that share
;; their overall skeleton but differ in localized regions -- the kind of
;; "variations on a base" seen in the inspiration triptych.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- all-nodes [rect]
  (cons rect (mapcat all-nodes (:children rect))))

(defn- depth-of
  "Tree depth of a rect, derived from its positional :id. Root has id \"\"."
  [rect]
  (count (:id rect)))

(defn- mutation-candidates
  "Rects in `root` eligible to have their subtree regenerated.

  Filters:
  - has children (leaves have nothing to regenerate)
  - depth in [min-depth, max-depth] -- min-depth ≥ 1 excludes the root,
    which would give an independent panel rather than a variation
  - smallest rect dimension ≥ min-dim -- skips nodes too small to show
    meaningful variation"
  [root {:keys [min-depth max-depth min-dim]}]
  (->> (all-nodes root)
       (filter (fn [r]
                 (and (seq (:children r))
                      (<= min-depth (depth-of r) max-depth)
                      (let [[w h] (:dim r)]
                        (>= (min w h) min-dim)))))))

(defn- replace-by-id
  "Walks `rect` and, when it finds a node whose :id equals `target-id`,
  replaces that node with `(f node)`. Other nodes are unchanged."
  [rect target-id f]
  (if (= (:id rect) target-id)
    (f rect)
    (update rect :children
            (fn [cs] (doall (map #(replace-by-id % target-id f) cs))))))

(defn- ancestor-or-descendant?
  "True when id-a and id-b are on the same root-to-leaf path (one is a
  prefix of the other). Used to skip mutation targets that would be
  wiped out by, or would wipe out, an already-chosen target."
  [id-a id-b]
  (or (.startsWith ^String id-a id-b)
      (.startsWith ^String id-b id-a)))

(s/def ::n-mutations pos-int?)
(s/def ::min-depth   nat-int?)
(s/def ::max-depth   nat-int?)
(s/def ::min-dim     number?)
(s/def ::mutate-opts (s/keys :opt-un [::n-mutations ::min-depth ::max-depth ::min-dim]))

(s/fdef mutate-pane
  :args (s/cat :base-pane ::pane
               :seed      ::seed
               :palette   (s/nilable ::palette)
               :opts      ::mutate-opts)
  :ret ::pane)
(defn mutate-pane
  "Returns a new pane derived from `base-pane` by regenerating up to
  `:n-mutations` random subtrees. Each picked subtree is replaced with
  a freshly-generated one (same root node, new children). Circles are
  regenerated from scratch afterward because rect corners have shifted.

  Already-picked subtrees are excluded from later picks in the same call
  (picking an ancestor would wipe out the earlier mutation; picking a
  descendant would sit inside a just-mutated area). If no candidates
  remain, the mutation loop exits early.

  Options (all have defaults):
    :n-mutations  how many subtrees to regenerate per panel   (default 2)
    :min-depth    shallowest mutatable depth (1 skips root)   (default 1)
    :max-depth    deepest mutatable depth                     (default 4)
    :min-dim      min(width, height) required to mutate       (default 3)"
  [base-pane seed pal
   {:keys [n-mutations min-depth max-depth min-dim]
    :or   {n-mutations 2
           min-depth   1
           max-depth   4
           min-dim     3}}]
  (set-random-seed! seed)
  (binding [palette   (or pal palette)
            seed-rect {:dim (:dim base-pane) :radius 0}]
    (let [filter-opts {:min-depth min-depth :max-depth max-depth :min-dim min-dim}]
      (loop [root      (:rect base-pane)
             remaining n-mutations
             used-ids  #{}]
        (let [candidates (->> (mutation-candidates root filter-opts)
                              (remove (fn [c]
                                        (some #(ancestor-or-descendant? (:id c) %)
                                              used-ids))))]
          (if (or (zero? remaining) (empty? candidates))
            (assoc base-pane
                   :rect root
                   :circles (some-circles root))
            (let [target (rand-nth candidates)
                  root'  (replace-by-id root (:id target) with-random-children)]
              (recur root' (dec remaining) (conj used-ids (:id target))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinated circles (for triptych-variation)
;;
;; Instead of each panel generating its own circles independently, sample
;; a shared curve in triptych-global coordinates and drop circles along
;; it. Each circle gets routed to whichever panel its x-coordinate falls
;; inside (points landing in gaps are discarded).
;;
;; The curve is a line from a random point on the left edge to a random
;; point on the right edge, modulated by a sinusoid perpendicular to the
;; line. Randomizing the two endpoint y's gives a different line angle
;; per seed; randomizing the sine phase varies the wave shape.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- v+ [[ax ay] [bx by]] [(+ ax bx) (+ ay by)])
(defn- v* [[x y] s] [(* x s) (* y s)])
(defn- vlerp [a b t] (v+ a (v* (v+ b (v* a -1)) t)))
(defn- vnorm [[x y]]
  (let [m (Math/sqrt (+ (* x x) (* y y)))]
    (if (zero? m) [0 0] [(/ x m) (/ y m)])))
;; 90° rotation -- sign doesn't matter since the sine modulation is signed.
(defn- vperp [[x y]] [(- y) x])

(defn- curve-point-at
  "Returns the (jitter-free) global [x y] point on the coordinated curve
  at parameter t ∈ [0, 1]. Pure -- RNG-deterministic fields (start, end,
  perp, phase, etc.) are passed in via `setup`."
  [{:keys [start end perp phase amplitude frequency panel-h]} t]
  (let [base (vlerp start end t)
        wave (* amplitude panel-h
                (Math/sin (+ (* 2 Math/PI frequency t) phase)))]
    (v+ base (v* perp wave))))

(def ^:private curve-samples
  "Dense sampling resolution for the polyline overlay. More = smoother
  curve; 200 is ample for a 90-unit-wide triptych."
  200)

(defn coordinated-circles
  "Returns {:circles <per-panel-map> :curves <per-panel-map>}, where each
  per-panel-map has :left/:center/:right keys and values are in that
  panel's local coordinate system. Circles are placed at sampled points;
  curves are the dense polyline tracing the underlying shape (useful as
  a dev overlay to see where circles should land).

  Triptych layout (all required):
    :panel-w    width of left (and right) panel in units
    :center-w   width of center panel in units
    :panel-h    panel height in units
    :gap        gap between panels in units

  Curve + sampling (all optional, all have defaults):
    :seed          RNG seed (required in practice)
    :palette       accent colors come from here (overrides dyn binding)
    :n             circles drawn across the triptych                (8)
    :jitter-along  wander along the curve, fraction of spacing      (0.3)
    :jitter-perp   random perp wander, fraction of panel-h          (0.2)
    :amplitude     sine-wave amplitude, fraction of panel-h         (0.25)
    :frequency     sine cycles across the full triptych width       (1.0)

  With amplitude=0, the curve is a straight line; higher amplitudes bow
  the curve perpendicular to the base line. Frequency controls how many
  wave cycles span the triptych."
  [{:keys [panel-w center-w panel-h gap seed
           n jitter-along jitter-perp amplitude frequency]
    :as   opts
    :or   {n            8
           jitter-along 0.3
           jitter-perp  0.2
           amplitude    0.25
           frequency    1.0}}]
  (set-random-seed! seed)
  (binding [palette (or (:palette opts) palette)]
    (let [total-w (+ panel-w gap center-w gap panel-w)
          ;; Random endpoints on the left/right triptych edges: a different
          ;; line angle per seed, while still terminating at the outer edges.
          start-y (* panel-h (rand))
          end-y   (* panel-h (rand))
          start   [0.0 start-y]
          end     [(double total-w) end-y]
          dir     (vnorm [(- (first end) (first start))
                          (- (second end) (second start))])
          perp    (vperp dir)
          ;; Random phase so the wave shape differs per seed even at fixed freq.
          phase   (* 2 Math/PI (rand))
          setup   {:start start :end end :perp perp :phase phase
                   :amplitude amplitude :frequency frequency :panel-h panel-h}
          ;; Left panel at x∈[0, panel-w]; center at [panel-w+gap, ...];
          ;; right at [panel-w+gap+center-w+gap, total-w]. Points whose x
          ;; falls in a gap are dropped. Each row carries [key x0 x1 pw]
          ;; -- the panel's width in units, needed for fit-circle's
          ;; bounds check (center and outer panels can differ).
          panels  [[:left   0.0                         (double panel-w) (double panel-w)]
                   [:center (double (+ panel-w gap))    (double (+ panel-w gap center-w)) (double center-w)]
                   [:right  (double (+ panel-w gap center-w gap)) (double total-w) (double panel-w)]]
          panel-at (fn [x]
                     (some (fn [[k x0 x1 pw]] (when (<= x0 x x1) [k x0 pw])) panels))
          rand-signed (fn [] (- (* 2.0 (rand)) 1.0))
          ;; Dense curve polylines, split by panel. No RNG -- deterministic
          ;; from setup. Computed BEFORE circle sampling so that circle
          ;; jitter RNG consumption doesn't change the curve shape (and so
          ;; callers that only want circles see the same output as before).
          curves
          (reduce
            (fn [acc i]
              (let [t (/ (double i) (dec curve-samples))
                    [px py] (curve-point-at setup t)]
                (if-let [[k x0] (panel-at px)]
                  (update acc k conj [(- px x0) py])
                  acc)))
            {:left [] :center [] :right []}
            (range curve-samples))
          circles
          (reduce
            (fn [acc i]
              (let [t       (/ (+ i 0.5 (* jitter-along (rand-signed))) n)
                    base    (vlerp start end t)
                    wave    (* amplitude panel-h
                               (Math/sin (+ (* 2 Math/PI frequency t) phase)))
                    rj      (* jitter-perp panel-h (rand-signed))
                    [px py] (v+ base (v* perp (+ wave rj)))
                    ;; Consume rad/color up-front so RNG state is
                    ;; independent of the panel-hit / fit outcome. Circles
                    ;; that don't fit are just dropped.
                    rad     (rand-nth (range 3 10))
                    color   (some-accent-color)]
                (if-let [[k x0 pw] (panel-at px)]
                  (if-let [fitted (fit-circle (- px x0) py rad pw panel-h
                                              (get acc k []))]
                    (update acc k conj {:rad rad :loc fitted :color color})
                    acc)
                  acc)))
            {:left [] :center [] :right []}
            (range n))]
      {:circles circles :curves curves})))

(st/instrument)
