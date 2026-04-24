(ns kinder.core
  "Pure generation logic for the kinder artwork: rectangle subdivision,
  coloring, circle placement, mutation, and the coordinated-circle curve
  for triptych variations. Shared between CLJ and CLJS via `.cljc` and
  the portable `kinder.rng`.

  ;; Data shapes:
  ;;   :color           ⇒ [h s b]  (3 ints, HSB 0-360/0-100/0-100)
  ;;   :palette         ⇒ {:main [h s b] :accent [[h s b] ...]
  ;;                       :accent-weights [positive-int ...]}
  ;;   :rect            ⇒ {:dim [w h] :loc [x y] :color [h s b]
  ;;                       :assigned-color [h s b] :radius number
  ;;                       :id string :children [rect ...]}
  ;;   :circle          ⇒ {:loc [x y] :rad number :color [h s b]}
  ;;   :pane            ⇒ {:rect rect :circles [circle ...]
  ;;                       :seed string :dim [w h]}

  All randomness is channeled through a boxed stateful RNG (`kinder.rng`).
  The public generator entry points accept a seed (string; may be nil for
  an ambient fresh seed) and build an RNG internally."
  (:require [kinder.rng :as rng]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn divisors [n]
  (->> (range 1 (inc n))
       (filter #(zero? (rem n %)))))

(defn- parse-int [s]
  #?(:clj  (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn weighted-selection [rng pairs]
  (let [total (reduce + (map second pairs))
        r (rng/rand-int rng total)]
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

(def anthro-1-palette
  {:main [57 8 93]
   :accent [[46 51 92]
            [15 55 80]
            [210 48 80]
            [86 34 55]]
   :accent-weights [1 2 1 1]})

(def anthro-2-palette
  {:main [57 8 93]
   :accent [[46 51 92]
            [15 55 80]
            [244 8 86]
            [160 10 82]]
   :accent-weights [1 2 1 1]})

(def anthro-3-palette
  {:main [57 8 93]
   :accent [[15 55 80]]
   :accent-weights [1]})

(def black-and-white-palette
  {:main [0 0 100]
   :accent [[0 0 0]]
   :accent-weights [1]})

(def palettes [kinder-palette
               black-and-white-palette])

(def ^:dynamic palette kinder-palette)
(def ^:dynamic seed-rect {:dim [0 0]})

(def ^:dynamic ^{:doc "Multiplier on the `(constantly [])` weight in every
  cond branch of `children`. 1.0 = current behavior. 0 = never stop
  subdividing (panels fill up with pattern). >1 = more empty cream blocks."}
  empty-weight-scale 1.0)

(def ^:dynamic ^{:doc "Exponent shaping divisor weighting in `horz-even-children`
  and its vertical twin. 1.0 = current behavior (small divisors favored,
  which produces dense micro-patterns when a strip is 1 unit wide). 0 =
  uniform (any divisor equally likely). Negative = favor large divisors
  (chunky splits instead of tight ones)."}
  divisor-bias 1.0)

(def ^:dynamic ^{:doc "Exponent shaping the preference for cutting along the
  rect's short axis vs the long axis. 1.0 = current behavior (10:1 in
  favor of the short-axis cut, which produces horizontal bands on tall
  panes). 0 = 50/50. Negative = favor long-axis cuts (vertical mullions
  on tall panes, a la Frank Lloyd Wright)."}
  cut-direction-bias 1.0)

(def ^:dynamic ^{:doc "Corner radius (in output pixels) applied to every
  subdivided cell rect. The outer pane frame stays rectangular regardless.
  Default 2 preserves current behavior; 0 = square corners everywhere;
  larger values = more pronounced rounding."}
  corner-radius 2.0)

(defn- direction-weights
  [bias]
  (let [favored   (Math/pow 10.0 (double bias))
        unfavored 1.0
        scale     (/ 1.0 (min favored unfavored))]
    [(max 1 (int (rng/round (* favored scale))))
     (max 1 (int (rng/round (* unfavored scale))))]))

(defn- scaled-empty-weight
  [w]
  (max 0 (int (rng/round (* (double w) (double empty-weight-scale))))))

(defn- main-color []
  (:main palette))

(defn- some-accent-color [rng]
  (let [accents (:accent palette)
        weights (:accent-weights palette)]
    (if (and (seq weights) (= (count accents) (count weights)))
      (weighted-selection rng (mapv vector accents weights))
      (rng/rand-nth rng accents))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coloring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- assign-some-color [rng rect parent-color]
  (let [{:keys [dim]} rect
        [w h] dim
        color (cond
                (or (> w 3) (> h 3))
                (weighted-selection rng [[parent-color 1]
                                         [(some-accent-color rng) 10]])
                :else
                (weighted-selection rng [[parent-color 10]
                                         [(some-accent-color rng) 1]]))]
    (assoc rect :assigned-color color)))

(defn- express-some-color [rng rect]
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
                           (map parse-int))
                      [0 0])
        id-indicates-color (= (mod id-a 2) (mod id-b 2))
        color (cond
                (not is-square) main
                is-large (weighted-selection rng [[main 10]
                                                  [accent 3]])
                id-indicates-color (weighted-selection rng [[accent 10]
                                                            [main 1]])
                :else (weighted-selection rng [[main 10]
                                               [accent 1]]))]
    (assoc rect :color color)))

(defn- assign-and-express-color [rng rect parent-color]
  (express-some-color rng (assign-some-color rng rect parent-color)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rect child-bearers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- flip-axes [child-bearer-f]
  (let [reversev (comp vec reverse)
        flip (fn flip [rect] (-> rect
                                  (update :dim reversev)
                                  (update :loc reversev)
                                  (update :children #(if (not-empty %)
                                                        (map flip %)
                                                        %))))]
    (fn [rng rect]
      (let [children (child-bearer-f rng (flip rect))]
        (doall (mapv flip children))))))

(defn- divisor-weights
  [divs]
  (let [n (count divs)]
    (mapv (fn [i]
            (max 1 (int (rng/round
                          (* 100.0
                             (Math/pow (double (- n i))
                                       (double divisor-bias)))))))
          (range n))))

(defn- horz-even-children [rng rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        divs (divisors h)
        div (weighted-selection rng (mapv vector divs (divisor-weights divs)))]
    (->> (range y (+ y h) div)
         (map-indexed #(assign-and-express-color rng
                                                 {:dim [w div]
                                                  :loc [x %2]
                                                  :id (str id %1)}
                                                 assigned-color))
         (doall))))
(def vert-even-children (flip-axes horz-even-children))

(defn- horz-sym-children [rng rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        h' (inc (rng/rand-int rng (int (/ h 3))))
        y'a y
        y'b (- (+ y h) h')
        r {:dim [w h']}]
    (->> [(assoc r :loc [x y'a])
          {:loc [x (+ y'a h')] :dim [w (- h h' h')]}
          (assoc r :loc [x y'b])]
         (map-indexed #(assoc %2 :id (str id %1)))
         (mapv #(assign-and-express-color rng % assigned-color))
         (doall))))

(def vert-sym-children (flip-axes horz-sym-children))

(defn- horz-stripe-sem-children
  "Bastard function -- renders not one but two 'levels' of children!
  Furthermore, it breaks some squares down to half-unit!

  Asserts that the rect is 1 unit wide!
  "
  [rng rect]
  {:pre [(= 1 (first (:dim rect)))]}
  (let [odd-or-even (rng/rand-int rng 2)
        children (->> (horz-even-children rng rect)
                      (map (fn more-children [rect]
                             (let [{:keys [id dim loc assigned-color]} rect
                                   [w h] dim
                                   [x y] loc
                                   should-probably-split (= odd-or-even
                                                            (mod (parse-int (str (last id)))
                                                                 2))
                                   should-split (or (and should-probably-split
                                                         (not= 0 (rng/rand-int rng 4)))
                                                    (= 0 (rng/rand-int rng 10)))
                                   children (if should-split
                                              (mapv #(assign-and-express-color rng % assigned-color)
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

(defn- horz-rand-children [rng rect]
  (let [{:keys [dim loc assigned-color id]} rect
        [w h] dim
        [x y] loc
        num-kids (+ 2 (rng/rand-int rng 4))
        ranges (->> (repeatedly #(rng/rand-nth rng (map inc (range y (+ y h)))))
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
                            (assign-and-express-color rng
                                                      {:dim [w (- yb ya)]
                                                       :loc [x ya]
                                                       :id (str id i)}
                                                      assigned-color))
                          ranges))]
    rs))
(def vert-rand-children (flip-axes horz-rand-children))

(defn- children [sym rand-f even stripe-sem]
  (fn [rng rect]
    (let [{:keys [dim]} rect
          [w h] dim
          seed-dim (:dim seed-rect)
          [seed-w seed-h] seed-dim
          is-pretty-small (and true
                               (< w (* 0.15 seed-w))
                               (< h (* 0.15 seed-h)))
          is-pretty-big (and (> w (* 0.5 seed-w))
                             (> h (* 0.5 seed-h)))
          is-very-big (and (> w (* 0.9 seed-w))
                           (> h (* 0.9 seed-h)))

          is-seed-rect (and (= w seed-w)
                            (= h seed-h))

          skinny 3
          is-skinny (or (<= w skinny) (<= h skinny))
          is-maximally-skinny (or (= w 1) (= h 1))

          is-long-and-maximally-skinny (and is-maximally-skinny
                                            (or (> w 10) (> h 10)))
          is-square (= w h)

          f (cond
              (and (<= w 1) (<= h 1))
              (constantly [])

              is-seed-rect
              (weighted-selection rng [[sym 1]])

              (and is-square (> w 6) (> h 6))
              (weighted-selection rng [[(constantly []) (scaled-empty-weight 4)]
                                       [sym 1]
                                       [rand-f 1]
                                       [even 1]])

              is-very-big
              (weighted-selection rng [[sym 6]
                                       [rand-f 3]])

              is-pretty-big
              (weighted-selection rng [[sym 6]
                                       [rand-f 3]
                                       [(constantly []) (scaled-empty-weight 4)]])

              is-long-and-maximally-skinny
              (weighted-selection rng [[even 10]
                                       [rand-f 10]
                                       [(constantly []) (scaled-empty-weight 10)]
                                       [stripe-sem 20]])

              is-maximally-skinny
              (weighted-selection rng [[even 10]
                                       [(constantly []) (scaled-empty-weight 4)]])

              is-skinny
              (weighted-selection rng [[even 10]
                                       [(constantly []) (scaled-empty-weight 6)]])

              is-pretty-small
              (weighted-selection rng [[even 2]
                                       [rand-f 1]
                                       [sym 1]
                                       [(constantly []) (scaled-empty-weight 10)]])

              :else
              (weighted-selection rng [[sym 4]
                                       [rand-f 8]
                                       [even 2]
                                       [(constantly []) (scaled-empty-weight 10)]]))
          kids (f rng rect)]
      (map #(assoc % :radius corner-radius)
           kids))))

(def horz-children (children horz-sym-children
                             horz-rand-children
                             horz-even-children
                             horz-stripe-sem-children))

(def vert-children (children vert-sym-children
                             vert-rand-children
                             vert-even-children
                             vert-stripe-sem-children))

(defn- make-direct-children [rng rect]
  (let [[w h] (:dim rect)
        is-vert (> h w)
        [fav unf] (direction-weights cut-direction-bias)]
    (cond
      (and (<= w 1) (<= h 1)) (or (:children rect) [])
      (<= w 1) (horz-children rng rect)
      (<= h 1) (vert-children rng rect)
      is-vert (weighted-selection rng [[(horz-children rng rect) fav]
                                       [(vert-children rng rect) unf]])
      :else (weighted-selection rng [[(horz-children rng rect) unf]
                                     [(vert-children rng rect) fav]]))))

(defn- with-some-direct-children [rng rect]
  (let [children (make-direct-children rng rect)]
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

(defn with-random-children [rng rect]
  (-> (with-some-direct-children rng rect)
      (update :children (partial map #(with-random-children rng %)))
      (update :children doall)))

(defn some-rect [rng dimensions]
  (let [accent (some-accent-color rng)
        main (main-color)]
    (-> (with-random-children rng {:dim            dimensions
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

(def ^:private min-visible-fraction
  "Circles whose visible fraction drops below this get nudged inward (or
  skipped if nudging fails). 0.5 means at least half the disk is in-pane
  -- tiny edge slivers are eliminated while the edge-flirting look is kept."
  0.5)

(defn- visible-fraction
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
  [[cx cy] rad [ox oy] other-rad]
  (let [dx     (- cx ox)
        dy     (- cy oy)
        d      (Math/sqrt (+ (* dx dx) (* dy dy)))
        target (* 1.001 (+ rad other-rad))]
    (cond
      (zero? d)       [(+ ox target) oy]
      (>= d target)   [cx cy]
      :else           [(+ ox (* (/ dx d) target))
                       (+ oy (* (/ dy d) target))])))

(def ^:private fit-max-tries 8)

(defn- fit-circle
  [cx cy rad w h existing]
  (let [r (/ rad 2.0)]
    (loop [x cx y cy tries 0]
      (cond
        (> tries fit-max-tries) nil

        (< (visible-fraction x y r w h) min-visible-fraction)
        (let [amount (max 0.5 (/ r 2.0))
              [x' y'] (nudge-toward-rect-center [x y] w h amount)]
          (if (and (= x' x) (= y' y))
            nil
            (recur x' y' (inc tries))))

        :else
        (if-let [c (conflicting-circle x y rad existing)]
          (let [[x' y'] (nudge-away-from [x y] rad (:loc c) (:rad c))]
            (recur x' y' (inc tries)))
          [x y])))))

(defn some-circle [rng rect circles]
  (let [rad (rng/rand-nth rng (range 3 10))
        [w h] (:dim rect)
        all-corners (all-corner-coords rect)
        candidates (map (fn [[x y]]
                          (let [jit #(rng/rand-nth rng (range -1 2 0.1))]
                            [(+ x (jit)) (+ y (jit))]))
                        all-corners)
        fitted (keep (fn [[x y]] (fit-circle x y rad w h circles)) candidates)]
    (if (empty? fitted)
      []
      [{:rad rad :loc (rng/rand-nth rng fitted) :color (some-accent-color rng)}])))


(defn some-circles [rng rect]
  (let [num-circles (rng/rand-nth rng (range 3 5))]
    (loop [circles []
           calls 0]
      (if (= calls num-circles)
        circles
        (recur (into circles
                     (some-circle rng rect circles))
               (inc calls))))))

(defn generate-pane
  "Outputs a kinder-symphony work. Demands dimensions in the form
   of [w h] — we refuse to generate random dimensions.
   Other parameters are optional. `:seed` is a string (or nil for a
   fresh ambient seed)."
  [dimensions & {:keys [seed palette
                        empty-weight-scale divisor-bias
                        cut-direction-bias corner-radius]}]
  (let [seed (or seed (rng/ambient-seed))
        rng  (rng/make-rng seed)
        default-palette (rng/rand-nth rng palettes)
        chosen-palette (or palette default-palette)
        ews (or empty-weight-scale kinder.core/empty-weight-scale)
        dbias (or divisor-bias kinder.core/divisor-bias)
        cbias (or cut-direction-bias kinder.core/cut-direction-bias)
        cr (or corner-radius kinder.core/corner-radius)]
    (binding [kinder.core/palette             chosen-palette
              seed-rect                       {:dim dimensions :radius 0}
              kinder.core/empty-weight-scale  (double ews)
              kinder.core/divisor-bias        (double dbias)
              kinder.core/cut-direction-bias  (double cbias)
              kinder.core/corner-radius       (double cr)]
      (let [rect (some-rect rng dimensions)
            circles (some-circles rng rect)]
        {:rect    rect
         :circles circles
         :seed    seed
         :dim     dimensions}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation (for triptych-variation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- all-nodes [rect]
  (cons rect (mapcat all-nodes (:children rect))))

(defn- depth-of
  [rect]
  (count (:id rect)))

(defn- mutation-candidates
  [root {:keys [min-depth max-depth min-dim]}]
  (->> (all-nodes root)
       (filter (fn [r]
                 (and (seq (:children r))
                      (<= min-depth (depth-of r) max-depth)
                      (let [[w h] (:dim r)]
                        (>= (min w h) min-dim)))))))

(defn- replace-by-id
  [rect target-id f rng]
  (if (= (:id rect) target-id)
    (f rng rect)
    (update rect :children
            (fn [cs] (doall (map #(replace-by-id % target-id f rng) cs))))))

(defn- ancestor-or-descendant?
  [id-a id-b]
  (or (str/starts-with? id-a id-b)
      (str/starts-with? id-b id-a)))

(defn mutate-pane
  "Returns a new pane derived from `base-pane` by regenerating up to
  `:n-mutations` random subtrees. Each picked subtree is replaced with a
  freshly-generated one. Circles are regenerated from scratch afterward
  because rect corners have shifted.

  `seed` is a string. Options (all have defaults):
    :n-mutations  number of subtrees to regenerate per panel     (20)
    :min-depth    shallowest mutatable depth                     (0)
    :max-depth    deepest mutatable depth                        (4)
    :min-dim      min(w, h) required to mutate                   (3)"
  [base-pane seed pal
   {:keys [n-mutations min-depth max-depth min-dim
           empty-weight-scale divisor-bias cut-direction-bias corner-radius]
    :or   {n-mutations 20
           min-depth   0
           max-depth   4
           min-dim     3}}]
  (let [rng   (rng/make-rng seed)
        ews   (or empty-weight-scale kinder.core/empty-weight-scale)
        dbias (or divisor-bias kinder.core/divisor-bias)
        cbias (or cut-direction-bias kinder.core/cut-direction-bias)
        cr    (or corner-radius kinder.core/corner-radius)]
    (binding [palette                         (or pal palette)
              seed-rect                       {:dim (:dim base-pane) :radius 0}
              kinder.core/empty-weight-scale  (double ews)
              kinder.core/divisor-bias        (double dbias)
              kinder.core/cut-direction-bias  (double cbias)
              kinder.core/corner-radius       (double cr)]
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
                     :circles (some-circles rng root))
              (let [target (rng/rand-nth rng candidates)
                    root'  (replace-by-id root (:id target) with-random-children rng)]
                (recur root' (dec remaining) (conj used-ids (:id target)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinated circles (for triptych-variation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- v+ [[ax ay] [bx by]] [(+ ax bx) (+ ay by)])
(defn- v* [[x y] s] [(* x s) (* y s)])
(defn- vlerp [a b t] (v+ a (v* (v+ b (v* a -1)) t)))
(defn- vnorm [[x y]]
  (let [m (Math/sqrt (+ (* x x) (* y y)))]
    (if (zero? m) [0 0] [(/ x m) (/ y m)])))
(defn- vperp [[x y]] [(- y) x])

(defn- curve-point-at
  [{:keys [start end perp phase amplitude frequency panel-h]} t]
  (let [base (vlerp start end t)
        wave (* amplitude panel-h
                (Math/sin (+ (* 2 Math/PI frequency t) phase)))]
    (v+ base (v* perp wave))))

(def ^:private curve-samples 200)

(defn coordinated-circles
  [{:keys [panel-w center-w panel-h gap seed
           n jitter-along jitter-perp amplitude frequency]
    :as   opts
    :or   {n            8
           jitter-along 0.3
           jitter-perp  0.2
           amplitude    0.25
           frequency    1.0}}]
  (let [rng (rng/make-rng seed)]
    (binding [palette (or (:palette opts) palette)]
      (let [total-w (+ panel-w gap center-w gap panel-w)
            start-y (* panel-h (rng/rand-double rng))
            end-y   (* panel-h (rng/rand-double rng))
            start   [0.0 start-y]
            end     [(double total-w) end-y]
            dir     (vnorm [(- (first end) (first start))
                            (- (second end) (second start))])
            perp    (vperp dir)
            phase   (* 2 Math/PI (rng/rand-double rng))
            setup   {:start start :end end :perp perp :phase phase
                     :amplitude amplitude :frequency frequency :panel-h panel-h}
            panels  [[:left   0.0                         (double panel-w) (double panel-w)]
                     [:center (double (+ panel-w gap))    (double (+ panel-w gap center-w)) (double center-w)]
                     [:right  (double (+ panel-w gap center-w gap)) (double total-w) (double panel-w)]]
            panel-at (fn [x]
                       (some (fn [[k x0 x1 pw]] (when (<= x0 x x1) [k x0 pw])) panels))
            rand-signed (fn [] (- (* 2.0 (rng/rand-double rng)) 1.0))
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
                      rad     (rng/rand-nth rng (range 3 10))
                      color   (some-accent-color rng)]
                  (if-let [[k x0 pw] (panel-at px)]
                    (if-let [fitted (fit-circle (- px x0) py rad pw panel-h
                                                (get acc k []))]
                      (update acc k conj {:rad rad :loc fitted :color color})
                      acc)
                    acc)))
              {:left [] :center [] :right []}
              (range n))]
        {:circles circles :curves curves}))))
