(ns kinder.rng
  "Portable seeded PRNG (mulberry32) shared between JVM and JS. The RNG is
  a boxed, stateful object — `make-rng` returns a volatile holding the
  32-bit word; `rand`, `rand-int`, and `rand-nth` mutate it in place and
  return the drawn value. This preserves call-site ergonomics while
  removing the `random-seed` library's JVM-global state.

  The public seed is a string (we hash it to a 32-bit int internally).
  Passing a number is also accepted and truncated to its low 32 bits."
  (:refer-clojure :exclude [rand-int rand-nth]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; 32-bit arithmetic helpers, identical on CLJ and CLJS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- u32
  "Coerce to 32-bit unsigned. CLJ: a long in [0, 2^32). CLJS: a Number
  in the same range (uint32 coercion via `>>> 0`)."
  [x]
  #?(:clj  (bit-and x 0xFFFFFFFF)
     :cljs (unsigned-bit-shift-right x 0)))

(defn- mul32
  "32-bit integer multiply, result as uint32. Mirrors js/Math.imul."
  [a b]
  #?(:clj  (u32 (unchecked-multiply-int (unchecked-int a) (unchecked-int b)))
     :cljs (u32 (js/Math.imul a b))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared half-up round (single source of truth for shared code)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn round
  "Half-up round to nearest long. Defined here (not via the host
  Math/round symbol) so shared code behaves identically on JVM and JS."
  [x]
  #?(:clj  (long (Math/floor (+ (double x) 0.5)))
     :cljs (long (js/Math.floor (+ x 0.5)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seed hashing (FNV-1a 32-bit)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- char-code [s i]
  #?(:clj  (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)))

(defn- string->u32 [s]
  (loop [i 0 h 2166136261]
    (if (>= i (count s))
      (u32 h)
      (recur (inc i) (mul32 (bit-xor h (char-code s i)) 16777619)))))

(defn- coerce-seed [seed]
  (cond
    (string? seed) (string->u32 seed)
    (number? seed) (u32 (long seed))
    :else (throw (ex-info "seed must be a string or number" {:seed seed}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RNG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ambient-seed
  "A fresh 8-char lowercase hex seed, portable across CLJ and CLJS."
  []
  #?(:clj  (subs (str (java.util.UUID/randomUUID)) 0 8)
     :cljs (subs (str (random-uuid)) 0 8)))

(defn make-rng
  "Build a fresh RNG keyed to `seed` (string or number). With no arg
  uses `ambient-seed`."
  ([] (make-rng (ambient-seed)))
  ([seed] (volatile! (coerce-seed seed))))

(defn- next-uint32!
  "Mutate rng to next state, return the produced uint32."
  [rng]
  (let [s   (u32 (+ @rng 0x6D2B79F5))
        _   (vreset! rng s)
        t1  (mul32 (bit-xor s (unsigned-bit-shift-right s 15))
                  (bit-or s 1))
        adj (mul32 (bit-xor t1 (unsigned-bit-shift-right t1 7))
                  (bit-or t1 61))
        t2  (u32 (bit-xor t1 (u32 (+ t1 adj))))]
    (u32 (bit-xor t2 (unsigned-bit-shift-right t2 14)))))

(defn rand-double
  "Uniform double in [0, 1)."
  [rng]
  (/ (double (next-uint32! rng)) 4294967296.0))

(defn rand-int
  "Uniform integer in [0, n)."
  [rng n]
  (int (* n (rand-double rng))))

(defn rand-nth
  "Random element of `coll`."
  [rng coll]
  (nth coll (rand-int rng (count coll))))
