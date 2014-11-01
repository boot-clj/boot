(ns boot.namespace
  (:require
   [clojure.java.io               :as io]
   [clojure.tools.namespace.track :as tnt]
   [clojure.tools.namespace.file  :as tnf]))

(defn dependents [src-dirs]
  (->> src-dirs
    (map io/file)
    (mapcat file-seq)
    (filter #(and (.isFile %) (.endsWith (.getName %) ".clj")))
    (tnf/add-files (tnt/tracker))
    ::tnt/deps
    :dependents))
