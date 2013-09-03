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
   :tasks         nil})

(defn -main [& args]
  (let [boot  (core/init! (assoc-in base-env [:system :argv] args))
        tmp   #(tmp/init! (tmp/registry (get-in @boot [:system :tmpregistry])))
        f     (io/file (get-in @boot [:system :bootfile]))]
    (assert (.exists f) (format "File '%s' not found." f))
    (core/run-next-task! boot (assoc (read-string (slurp f)) :tmp (tmp))) 
    (while (core/run-next-task! boot))
    nil))
