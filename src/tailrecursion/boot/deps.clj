(ns tailrecursion.boot.deps
  (:import
    [java.util.jar JarFile]
    [java.util.zip ZipFile])
  (:require
    [cemerick.pomegranate.aether :refer [resolve-dependencies]]
    [tailrecursion.boot.kahnsort :refer [topo-sort]]))

(defn entries-1 [jar]
  (->> (.entries jar)
    enumeration-seq
    (map #(vector (.getName %) (.getInputStream jar %)))
    (into {})))

(defn deps [coords]
  (->> (resolve-dependencies :coordinates coords)
    (topo-sort)
    (map #(:file (meta %)))
    (filter #(.endsWith (.getPath %) ".jar"))
    (map #(JarFile. %))
    (map #(vector (.getName %) (entries-1 %)))))
