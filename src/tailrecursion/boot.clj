(ns tailrecursion.boot
  (:require [clojure.string                 :as string]
            [clojure.java.io                :as io]
            [clojure.pprint                 :refer [pprint]]
            [tailrecursion.boot.core        :as core]
            [tailrecursion.boot.tmpregistry :as tmp])
  (:import java.lang.management.ManagementFactory)
  (:gen-class))

(def base-env
  {:project       nil
   :version       nil
   :dependencies  #{}
   :directories   #{}
   :repositories  #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"}
   :system        {:cwd         (io/file (System/getProperty "user.dir"))
                   :home        (io/file (System/getProperty "user.home")) 
                   :jvm-opts    (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                   :bootfile    (io/file (System/getProperty "user.dir") "boot.clj")
                   :userfile    (io/file (System/getProperty "user.home") ".boot.clj")
                   :tmpregistry nil}
   :tmp           nil
   :tasks         {:help {:main '[tailrecursion.boot.core/help-task]}}})

(defmacro try* [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defn exists? [f]
  (when (try* (.exists f)) f))

(defn read-file [f]
  (try (read-string (str "(" (try (slurp f) (catch Throwable x)) ")"))
    (catch Throwable e
      (throw (Exception.
               (format "%s (Can't read forms from file)" (.getPath f)) e)))))

(defn read-config [f]
  (let [config (first (read-file f))
        asrt-m #(do (assert (map? %1) %2) %1)]
    (asrt-m config (format "%s (Configuration must be a map)" (.getPath f)))))

(defn read-cli-args [args]
  (let [s (try (read-string (str "(" (string/join " " args) ")"))
            (catch Throwable e
              (throw (Exception. "Can't read command line forms" e))))]
    (map #(if (vector? %) % [%]) s)))

(defn merge-in-with [f ks & maps]
  (->> maps (map #(assoc-in {} ks (get-in % ks))) (apply merge-with f)))

(defn -main [& args]
  (let [sys   (:system base-env)
        argv  (or (seq (read-cli-args args)) (list ["help"]))
        mktmp #(tmp/init! (tmp/registry (io/file ".boot" "tmp")))
        usr   (when-let [f (exists? (:userfile sys))] (read-config f))
        cfg   (read-config (:bootfile sys))
        deps  (merge-in-with into [:dependencies] base-env usr cfg)
        dirs  (merge-in-with into [:directories] base-env usr cfg)
        repo  (merge-with #(some identity %&)
                (merge-in-with into [:repositories] {:repositories #{}} usr cfg)
                (select-keys base-env [:repositories])) 
        tasks (merge-in-with into [:tasks] base-env usr cfg)
        sys   (merge-with into sys {:argv argv :tmpregistry (mktmp)})
        boot  (core/init! base-env)]
    (swap! boot merge usr cfg deps dirs repo tasks {:system sys})
    ((core/compose-tasks! boot) (core/make-event))
    nil))
