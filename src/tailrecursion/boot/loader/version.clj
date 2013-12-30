(ns tailrecursion.boot.loader.version
  (:require [clojure.java.io :refer [resource]]))

(defn info
  "Returns a map of version information for tailrecursion.boot.loader"
  []
  (let [[_ proj vers & kvs] (try (read-string (slurp (resource "project.clj")))
                                 (catch Throwable _))
        {:keys [description url license]} (->> (partition 2 kvs)
                                               (map (partial apply vector))
                                               (into {}))]
    {:proj        proj,
     :vers        vers,
     :description description,
     :url         url,
     :license     license}))
