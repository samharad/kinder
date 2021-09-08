(ns kinder.intellij
  (:require [tubular.core :as tubular]
            [clojure.java.io :refer [reader]]
            [clojure.core.server :as server]
            [clojure.core.async :refer [go]])
  (:import (java.io PipedWriter PipedReader)))

(def piped-writer (PipedWriter.))
(def piped-reader (PipedReader. piped-writer))


(defn start-ide-prepl! [existing-ide-repl-port ide-prepl-port]
  (binding [*in* (reader
                   (char-array
                     (str "(clojure.core.server/start-server {:accept 'clojure.core.server/io-prepl\n                 :address \"127.0.0.1\"\n                 :port " ide-prepl-port "\n                 :name \"prepl\"})\n:repl/quit\n")))
            *out* (java.io.StringWriter.)]
    (tubular/connect existing-ide-repl-port)))

(defn connect-to-ide-prepl [port]
  (go
    (server/remote-prepl "localhost"
                         port
                         piped-reader
                         println)))

(defn send-form [form]
  (do
    (.write piped-writer (str form "\n"))
    (.flush piped-writer)))

(defn init! []
  (start-ide-prepl! 40000 40001)
  (connect-to-ide-prepl 40001))

(comment
  (init!)
  (send-form "(.toString (com.intellij.openapi.vfs.VirtualFileManager/getInstance))")
  (send-form "(+ 3 3)"))

#_(comment
    "So what I basically need here:
  - User needs to have enabled and started the IDE REPL.
  - Then, my library sends over the form required for starting a
    PREPL on a NEW port:"
    (start-server {:accept 'clojure.core.server/io-prepl
                   :address "127.0.0.1"
                   :port 40001
                   :name "prepl"})

    "- Then, we establish a pREPL connection to it using:"
    (remote-prepl "localhost" 40001 *in* println)

    " But with different in/out streams. The in stream is a buffer
  to which we write; the out-fn handles exceptions or, hopefully,
  proper return values.

  Note that something weird happens when the IDE pREPL returns a tagged
  literal like #object['foobar'] -- something complains about not having
  a tagged literal handler for it.
  "

    "
  - A means of upgrading the IDE socket repl to a
    p-style-REPL, possibly Unrepl.
  - A Clojure client for the upgraded REPL.
  - Functions for working with IntelliJ.

  Of course, to start I could just fire commands to
  the IDE REPL, and hope they work.

  BUT I need to think about the use case more first,
  for sure. Currently I have none. Because if my plan
  is to programmatically modify a source file, that's
  a little silly, since unsaved edits might be present
  in the IntelliJ (or emacs) buffer. It's overall a
  silly task that prolly needs abandoned.

  ")


#_(defn foo []
    (binding [*in* (clojure.java.io/reader
                     (char-array
                       (str "(+ 1 1)" \newline \return
                            "(flush)" \newline \return
                            "(java.lang.Thread/sleep 1000)" \newline \return)))
              *out* (java.io.StringWriter.)]
      (tubular/connect 40000)
      (.toString *out*)))

#_(.syncRefresh
    (com.intellij.openapi.vfs.VirtualFileManager/getInstance))