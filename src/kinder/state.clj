(ns kinder.state
  (:require [kinder.core :as core]
            [clojure.spec.alpha :as s]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(comment
  "TODO:
    - 2x2s, 3x3s should be more likely to mutate color
    - Make larger accent squares just slightly more common
    - Make the 'rand' child-gen less random, per source
    - I know why the checkering is inconsistent. It's because a box
      might first divide evenly into stripes, then each of those may
      divide randomly, then checker.
    - Utils for marking the save-file for particularly good ones
    - More palettes

   CHECKER-COLORS EXPLANATION:
    - Checker-colors. I think this requires either: coloring a box
      with respect to its parent, or just doing the coloring as a
      second pass.
    - A parent box can be 'red'. And then, when I give it covering
      even-spaced children, those can take be colored in alternating
      (parent-color, plain, parent-color, plain...). But the problem
      is, we alternate stripes, and then when we go to color a white
      stripe, which color do we choose... you kind of want the grand-
      parent color. Hmmmmm.
      Or, each box has an indexed-based ID.
      Root-box: ''
      Children: ['0', '1', '2']
      Children of that: [['00', '01', '02'],
                         ['10', '11', '12'],
                         ['20', '21', '22']]
      Boxes inherit the color-assignment of their parent, but only
      express it based on their ID, particularly its last two digits
      (if they are both even or both odd), combined with some jitter.
      Assigned color is always an accent.
      Assigned color might 'mutate' in a child, randomly. Much more
      likely to occur when the parent is a large box. Also depends
      on the child-gen method: mutation is more likely for random-gen,
      less likely for even-gen.
  ")

(s/def ::render-depth (s/and int? (complement neg?)))
(s/def ::state (s/keys :req-un [::core/pane ::render-depth]))

(defn- step-state [state]
  (let [{:keys [render-depth pane]} state]
    (if (= pane (core/take-depth render-depth pane))
      state
      (update state :render-depth inc))))

(defn- back-state [state]
  (let [{:keys [render-depth]} state]
    (if (= render-depth 0)
      state
      (update state :render-depth dec))))


(defn- all-done [state]
  (let [{:keys [pane render-depth]} @state]
    (= pane (core/take-depth render-depth pane))))



(defn init-state [dimensions]
  (let [pane (core/generate-pane dimensions)]
    (-> {:pane pane}
        (assoc :render-depth 0))))

(defn re-init! [state]
  (swap! state #(init-state (:dim (:pane %)))))

(defn step! [state]
  (if (all-done state)
    (re-init! state)
    (swap! state step-state)))

(defn step-back! [state]
  (swap! state back-state))

(defn complete! [state]
  (when (all-done state)
    (re-init! state))
  (while (not (all-done state))
    (step! state)))

