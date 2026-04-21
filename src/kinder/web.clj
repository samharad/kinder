(ns kinder.web
  (:require [kinder.core :as core]
            [kinder.svg :as svg]
            [kinder.cli :as cli]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import (java.util Random)
           (java.net URLDecoder)))

(def ^:private palette-map
  {"kinder" core/kinder-palette
   "red"    core/red-palette
   "orange" core/orange-palette})

(def ^:private defaults
  {:width 30 :height 70 :unit 10 :stroke-weight 0.2 :palette "kinder" :gap 1})

(defn- random-seed []
  (-> (Random.) .nextLong))

(defn- render-single [{:keys [width height unit stroke-weight palette seed]}]
  (let [pal  (get palette-map palette)
        pane (cli/make-pane [width height] seed pal)]
    (svg/render pane unit stroke-weight)))

(defn- render-triptych [{:keys [width height unit stroke-weight palette gap seed mode]}]
  (let [pal       (get palette-map palette)
        center-w  (if (= "triptych-equal" mode) width (* 2 width))
        left      (cli/make-pane [width height] seed pal)
        center    (cli/make-pane [center-w height] (+ seed 1) pal)
        right     (cli/make-pane [width height] (+ seed 2) pal)]
    (svg/render-triptych left center right unit stroke-weight gap)))

(def ^:private modes #{"single" "triptych" "triptych-equal"})

(defn- build [params]
  (let [seed   (or (:seed params) (random-seed))
        mode   (get modes (:mode params) "single")
        params (merge defaults params {:seed seed :mode mode})
        body   (if (str/starts-with? mode "triptych")
                 (render-triptych params)
                 (render-single params))]
    {:svg body :seed seed :mode mode}))

(defn- save [params]
  (let [{:keys [svg seed]} (build params)
        path (cli/output-path seed)]
    (io/make-parents path)
    (spit path svg)
    {:path path :seed seed}))

;; -- inspiration --

(def ^:private inspiration-dir "inspiration")
(def ^:private image-exts #{"jpg" "jpeg" "png" "gif" "webp"})

(defn- ext-of [name]
  (let [i (.lastIndexOf ^String name ".")]
    (when (pos? i) (str/lower-case (subs name (inc i))))))

(defn- list-inspiration []
  (let [dir (io/file inspiration-dir)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isFile ^java.io.File %))
           (map #(.getName ^java.io.File %))
           (filter #(contains? image-exts (ext-of %)))
           sort
           vec)
      [])))

(def ^:private content-types
  {"jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "png"  "image/png"
   "gif"  "image/gif"
   "webp" "image/webp"})

(defn- inspiration-file-response [filename]
  (if (or (str/includes? filename "/")
          (str/includes? filename "\\")
          (str/includes? filename ".."))
    {:status 400 :body "bad filename"}
    (let [f (io/file inspiration-dir filename)
          ext (ext-of filename)]
      (if (and (.isFile f) (contains? image-exts ext))
        {:status 200
         :headers {"content-type" (get content-types ext)
                   "cache-control" "max-age=3600"}
         :body (io/input-stream f)}
        {:status 404 :body "not found"}))))

;; -- http --

(defn- parse-query [qs]
  (if (str/blank? qs)
    {}
    (into {}
      (for [pair (str/split qs #"&")]
        (let [[k v] (str/split pair #"=" 2)]
          [(keyword k) (URLDecoder/decode (or v "") "UTF-8")])))))

(defn- coerce [{:keys [mode seed gap]}]
  (cond-> {}
    mode (assoc :mode mode)
    (and seed (not (str/blank? (str seed))))
    (assoc :seed (if (number? seed) (long seed) (Long/parseLong seed)))
    (and gap (not (str/blank? (str gap))))
    (assoc :gap (if (number? gap) (double gap) (Double/parseDouble gap)))))

(defn- json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-str body)})

(defn- html-response [body]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn- handler [{:keys [uri request-method query-string body]}]
  (cond
    (and (= :get request-method) (= "/" uri))
    (html-response (slurp (io/resource "public/index.html")))

    (and (= :get request-method) (= "/generate" uri))
    (json-response 200 (build (coerce (parse-query query-string))))

    (and (= :post request-method) (= "/save" uri))
    (let [payload (json/read-str (slurp body) :key-fn keyword)]
      (json-response 200 (save (coerce payload))))

    (and (= :get request-method) (= "/inspiration" uri))
    (json-response 200 {:images (list-inspiration)})

    (and (= :get request-method) (str/starts-with? uri "/inspiration/"))
    (inspiration-file-response (URLDecoder/decode (subs uri (count "/inspiration/")) "UTF-8"))

    :else
    {:status 404 :headers {"content-type" "text/plain"} :body "not found"}))

(defn -main [& _]
  (let [port 8080]
    (http/run-server #'handler {:port port})
    (println (str "kinder web listening on http://localhost:" port))
    @(promise)))
