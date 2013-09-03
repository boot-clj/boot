(ns tailrecursion.boot
  (:require [clojure.java.io        :as io]
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
                   :tmpregistry (io/file ".boot" "tmp")}
   :tmp           nil
   :main          'tailrecursion.boot.core/usage-task
   :tasks         nil})

(defn read-file [f]
  (try (read-string (str "(" (try (slurp f) (catch Throwable x)) ")"))
    (catch Throwable e
      (throw (Exception. (format "%s (Can't read forms from file)" (.getPath f)) e)))))

(defn -main [& args]
  (let [boot  (core/init! (assoc-in base-env [:system :argv] args))
        tmp   #(tmp/init! (tmp/registry (get-in @boot [:system :tmpregistry])))]
    (let [form (first (read-file (io/file (get-in @boot [:system :bootfile]))))]
      (core/run-next-task! boot (assoc form :tmp (tmp))) 
      (while (core/run-next-task! boot)) 
      nil)))
