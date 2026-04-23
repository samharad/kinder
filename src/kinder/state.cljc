(ns kinder.state
  "Pure transitions on the `{:pane :render-depth}` state map. Generation
  lives in `kinder.core`; atom management lives at the edges (browser,
  CLI)."
  (:require [kinder.core :as core]))

(defn init-state
  "Initial state for an already-generated `pane`."
  [pane]
  {:pane pane :render-depth 0})

(defn step-state
  "Advance render-depth one level unless already fully revealed."
  [state]
  (let [{:keys [render-depth pane]} state]
    (if (= pane (core/take-depth render-depth pane))
      state
      (update state :render-depth inc))))

(defn back-state
  "Go one render-depth level back (clamped at 0)."
  [state]
  (let [{:keys [render-depth]} state]
    (if (= render-depth 0)
      state
      (update state :render-depth dec))))

(defn all-done?
  "True when render-depth has caught up to the full tree."
  [state]
  (let [{:keys [pane render-depth]} state]
    (= pane (core/take-depth render-depth pane))))

(defn complete-state
  "Loop step-state until the pane is fully revealed."
  [state]
  (loop [s state]
    (if (all-done? s)
      s
      (recur (step-state s)))))
