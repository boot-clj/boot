;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.boot
  (:require [clojure.string                 :as string]
            [clojure.java.io                :as io]
            [clojure.pprint                 :refer [pprint]]
            [tailrecursion.boot.core        :as core]
            [tailrecursion.boot.tmpregistry :as tmp]
            [tailrecursion.boot.gitignore   :as git])
  (:import java.lang.management.ManagementFactory)
  (:gen-class))

(def base-env
  (fn []
    {:project       nil
     :version       nil
     :dependencies  []
     :src-paths     #{}
     :src-static    #{}
     :repositories  #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"}
     :require-tasks '#{[tailrecursion.boot.core.task :refer [help]]}
     :test          "test"
     :target        "target"
     :resources     "resources"
     :public        "resources/public"
     :system        {:cwd         (io/file (System/getProperty "user.dir"))
                     :home        (io/file (System/getProperty "user.home"))
                     :jvm-opts    (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                     :bootfile    (io/file (System/getProperty "user.dir") "boot.edn")
                     :userfile    (io/file (System/getProperty "user.home") ".boot.edn")
                     :tmpregistry (tmp/init! (tmp/registry (io/file ".boot" "tmp")))
                     :gitignore   (git/make-gitignore-matcher)}
     :tasks         {}}))

(defn exists? [f]
  (when (core/guard (.exists f)) f))

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
  (let [base  (base-env)
        sys   (:system base)
        argv  (or (seq (read-cli-args args)) (list ["help"]))
        usr   (when-let [f (exists? (:userfile sys))] (read-config f))
        cfg   (read-config (:bootfile sys))
        deps  (merge-in-with into [:dependencies] base usr cfg)
        dirs  (merge-in-with into [:src-paths] base usr cfg)
        reqs  (merge-in-with into [:require-tasks] base usr cfg)
        repo  (merge-with #(->> %& (filter seq) first)
                (merge-in-with into [:repositories] {:repositories #{}} usr cfg)
                (select-keys base [:repositories]))
        tasks (merge-in-with into [:tasks] base usr cfg)
        sys   (merge-with into sys {:argv argv})
        boot  (core/init! base)]
    (locking boot
      (swap! boot merge usr cfg deps dirs reqs repo tasks {:system sys})
      (swap! boot core/require-tasks))
    (let [app (core/create-app! boot)]
      (app (->> (:src-paths @boot)
                (map io/file)
                (mapcat file-seq)
                (filter #(.isFile %))
                (remove (partial core/ignored? boot))
                set
                (assoc (core/make-event) :src-files))))
    (System/exit 0)))
