(ns tailrecursion.boot-classloader
  (:require
   [clojure.string                          :as string]
   [cemerick.pomegranate.aether             :as aether]
   [tailrecursion.boot-classloader.kahnsort :as kahn])
  (:import
   [org.springframework.util AntPathMatcher])
  (:gen-class))

(defn warn
  [& more]
  (binding [*out* *err*] (apply printf more) (flush)))

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(def exclude-clj (partial exclude ['org.clojure/clojure]))

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error}]
  (when (and (.endsWith name ".jar") (= type :started))
    (warn "Retrieving %s from %s\n" name repo)))

(defn ^:from-leiningen build-url
  "Creates java.net.URL from string"
  [url]
  (try (java.net.URL. url)
       (catch java.net.MalformedURLException _
         (java.net.URL. (str "http://" url)))))

(defn ^:from-leiningen get-non-proxy-hosts []
  (let [system-no-proxy (System/getenv "no_proxy")]
    (if (not-empty system-no-proxy)
      (->> (string/split system-no-proxy #",")
           (map #(str "*" %))
           (string/join "|")))))

(defn ^:from-leiningen get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
     (if-let [proxy (System/getenv key)]
       (let [url (build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host            (.getHost url)
          :port            (.getPort url)
          :username        username
          :password        password
          :non-proxy-hosts (get-non-proxy-hosts)}))))

(defn resolve-dependencies!*
  [deps repos]
  (let [deps (mapv exclude-clj deps)]
    (aether/resolve-dependencies
      :coordinates        deps
      :repositories       (zipmap repos repos)
      :transfer-listener  transfer-listener
      :proxy              (get-proxy-settings))))

(defn resolve-dependencies!
  [deps repos]
  (->> (resolve-dependencies!* deps repos)
    kahn/topo-sort
    (map (fn [x] {:dep x :jar (.getPath (:file (meta x)))}))))

(defn glob-match? [pattern path]
  (.match (AntPathMatcher.) pattern path))

(defn -main
  "I don't do a whole lot ... yet. (or ever.)"
  [& args]
  (println "Hello, World!"))
