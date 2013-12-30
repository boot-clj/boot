;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.boot.loader
  (:require [clojure.string                    :as string]
            [clojure.java.io                   :as io]
            [cemerick.pomegranate              :as pom]
            [clojure.pprint                    :refer [pprint]]
            [tailrecursion.boot.loader.version :as version])
  (:gen-class))

(defmacro guard [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defn exists? [f]
  (when (guard (.exists f)) f))

(defn read-file [f]
  (try (read-string (str "(" (try (slurp f) (catch Throwable x)) ")"))
    (catch Throwable e
      (throw (Exception.
               (format "%s (Can't read forms from file)" (.getPath f)) e)))))

(defn read-config [f]
  (let [config (first (read-file f))
        asrt-m #(do (assert (map? %1) %2) %1)]
    (asrt-m config (format "%s (Configuration must be a map)" (.getPath f)))))

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error}]
  (when (.endsWith name ".jar")
    (case type
      :started              (printf "Retrieving %s from %s\n" name repo)
      (:corrupted :failed)  (when err (printf "Error: %s\n" (.getMessage err)))
      nil)
    (flush)))

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

(defn add-dependencies! [deps repos]
  (let [deps (mapv (partial exclude ['org.clojure/clojure]) deps)]
    (pom/add-dependencies :coordinates        deps
                          :repositories       (zipmap repos repos)
                          :transfer-listener  transfer-listener
                          :proxy              (get-proxy-settings))))

(defn -main [& args]
  (let [cfg   (read-config (io/file "boot.edn"))
        dep?  #(= 'tailrecursion/boot.core (first %))
        deps  (->> cfg :dependencies (filter dep?) vec)
        repos (or (:repositories cfg)
                  #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"})]
    (assert (seq deps) "No boot.core dependency specified.")
    (add-dependencies! deps repos)
    (require 'tailrecursion.boot)
    (let [main (find-var (symbol "tailrecursion.boot" "-main"))
          loader-info {:boot-version (version/info)}]
      (try (apply (partial main loader-info) args)
        (catch Throwable e
          (.printStackTrace e)
          (System/exit 1))))
    (System/exit 0)))
