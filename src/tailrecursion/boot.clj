(ns tailrecursion.boot
  (:require [clojure.string                 :as string]
            [clojure.java.io                :as io]
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
                   :user-cfg    (io/file (System/getProperty "user.home") ".boot.clj")
                   :tmpregistry nil}
   :tmp           nil
   :tasks         {:help {:main 'tailrecursion.boot.core/help-task}}})

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
  (let [config (first (read-file f))]
    (assert (map? config)
            (format "%s (Boot configuration must be a map)" (.getPath f)))
    config))

(defn read-cli-args [args]
  (let [s (try (read-string (str "(" (string/join " " args) ")"))
            (catch Throwable e
              (throw (Exception. "Can't read command line forms" e))))]
    (map #(if (vector? %) % [%]) s)))

(defn -main [& args]
  (let [sys   (:system base-env)
        argv  (or (seq (read-cli-args args)) (list ["help"]))
        mktmp #(tmp/init! (tmp/registry (io/file ".boot" "tmp")))
        cfg   (-> (when-let [f (exists? (:user-cfg sys))] (read-config f))
                (merge (read-config (:bootfile sys))))
        tasks (merge-with into (:tasks base-env) (:tasks cfg))
        sys   (merge-with into (:system base-env) {:argv argv :tmpregistry (mktmp)})
        boot  (core/init! base-env)]
    (swap! boot merge cfg {:tasks tasks} {:system sys})
    ((core/compose-tasks! boot) (core/make-event))
    nil))
