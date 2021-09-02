(ns kinder.intellij
  (:require [tubular.core :as tubular]
            []))

(comment
  "So what I basically need here:
  - A means of upgrading the IDE socket repl to a
    p-style-REPL, possibly Unrepl.
  - A Clojure client for the upgraded REPL.
  - Functions for working with IntelliJ.
  ")


(defn foo []
  (binding [*in* (clojure.java.io/reader
                   (char-array
                     (str "(+ 1 1)" \newline \return
                          "(flush)" \newline \return
                          "(java.lang.Thread/sleep 1000)" \newline \return)))
            *out* (java.io.StringWriter.)]
    (tubular/connect 40000)
    (.toString *out*)))

(foo)