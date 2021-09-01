(ns kinder.render
  "Here is where I would generate many high-res images
  using, hopefully, a headless configuration."
  (:require [kinder.state :as st]
            [kinder.draw :as draw]
            [quil.core :as q]))

(defonce state (atom (st/init-state [30 60])))
(st/complete! state)
(def draw (partial draw/draw state))

(defn -main [& args]
  (println "Rendering...")
  (q/sketch :title "Kinder-Big"
            :setup (constantly nil)
            :settings (constantly nil)
            :draw kinder.render/draw
            :features [:exit-on-close]
            :size [400 700])
  (println "Done!"))

(-main)
