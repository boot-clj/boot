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
                   :jvm-opts    (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                   :bootfile    (io/file (System/getProperty "user.dir") "boot.clj")
                   :tmpregistry nil}
   :tmp           nil
   :tasks         {:help {:main 'tailrecursion.boot.core/help-task}}})

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
  (let [argv  (or (seq (read-cli-args args)) (list ["help"]))
        mktmp #(tmp/init! (tmp/registry (io/file ".boot" "tmp")))
        form  (read-config (io/file (get-in base-env [:system :bootfile])))
        tasks (merge-with into (:tasks base-env) (:tasks form))
        sys   (merge-with into (:system base-env) {:argv argv :tmpregistry (mktmp)})
        boot  (core/init! (merge base-env {:tasks tasks} {:system sys}))]
    (while (core/run-next-task! boot)) 
    nil))
