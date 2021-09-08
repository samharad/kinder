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

(defmacro send-form [form]
  `(do
     (.write piped-writer (str (quote ~form) "\n"))
     (.flush piped-writer)))

(defn init! []
  (start-ide-prepl! 40000 40001)
  (connect-to-ide-prepl 40001))

(comment
  (init!)
  (send-form (.toString (com.intellij.openapi.vfs.VirtualFileManager/getInstance)))
  (send-form (+ 3 3)))

(defn save-all []
  (send-form
    (let [application (com.intellij.openapi.application.ApplicationManager/getApplication)]
      (.invokeAndWait application
                      #(.saveAll application)))))

(defn sync-refresh-all []
  (send-form
    (let [application (com.intellij.openapi.application.ApplicationManager/getApplication)
          file-manager (com.intellij.openapi.vfs.VirtualFileManager/getInstance)]
      (.invokeAndWait application
                      #(.syncRefresh file-manager)))))
(sync-refresh-all)

(defn code-header [s]
  (save-all)
  (Thread/sleep 1000)
  (let [this-file *file*]
    (when this-file
      (let [contents (slurp this-file)
            updated (str/replace contents
                                 (str "(code-header \"" s "\")")
                                 (str ";;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;"
                                      \newline
                                      ";; " s
                                      \newline
                                      ";;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;"))]
        (spit this-file updated))))
  (sync-refresh-all))