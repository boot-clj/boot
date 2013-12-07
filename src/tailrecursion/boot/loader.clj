;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.boot.loader
  (:require [clojure.string                 :as string]
            [clojure.java.io                :as io]
            [cemerick.pomegranate           :as pom]
            [clojure.pprint                 :refer [pprint]])
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

(defn add-dependencies! [deps repos]
  (let [deps (mapv (partial exclude ['org.clojure/clojure]) deps)]
    (pom/add-dependencies :coordinates        deps
                          :repositories       (zipmap repos repos)
                          :transfer-listener  transfer-listener)))

(defn -main [& args]
  (let [cfg   (read-config (io/file "boot.edn"))
        dep?  #(= 'tailrecursion/boot.core (first %))
        deps  (->> cfg :dependencies (filter dep?) vec)
        repos (or (:repositories cfg)
                  #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"})]
    (assert (seq deps) "No boot.core dependency specified.")
    (add-dependencies! deps repos)
    (require 'tailrecursion.boot)
    (let [main (find-var (symbol "tailrecursion.boot" "-main"))]
      (try (apply main args)
        (catch Throwable e
          (.printStackTrace e)
          (System/exit 1))))
    (System/exit 0)))
