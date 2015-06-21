(ns boot.namespace
  (:require
   [clojure.java.io               :as io]
   [clojure.tools.namespace.track :as tntrack]
   [clojure.tools.namespace.file  :as tnfile]
   [clojure.tools.namespace.find  :as tnfind]))

(defn dependents [src-dirs]
  (->> src-dirs
    (map io/file)
    (mapcat file-seq)
    (filter #(and (.isFile %) (.endsWith (.getName %) ".clj")))
    (tnfile/add-files (tntrack/tracker))
    ::tntrack/deps
    :dependents))

(defn find-namespaces-in-dirs [dirs]
  (mapcat #(tnfind/find-namespaces-in-dir (io/file %)) dirs))
