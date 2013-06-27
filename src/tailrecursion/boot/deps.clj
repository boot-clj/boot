(ns tailrecursion.boot.deps
  (:import
    [java.util.jar JarFile]
    [java.util.zip ZipFile])
  (:require
    [tailrecursion.boot          :as boot]
    [tailrecursion.boot.kahnsort :refer [topo-sort]]
    [cemerick.pomegranate.aether :refer [resolve-dependencies]]))

(defn entries [jar]
  (->> (.entries jar)
    enumeration-seq
    (map #(vector (.getName %) (.getInputStream jar %)))
    (into {})))

(defn deps []
  (let [{repos :repositories coords :dependencies} (:boot @boot/env)]
    (->> (resolve-dependencies :repositories repos :coordinates coords)
      (topo-sort)
      (map #(:file (meta %)))
      (filter #(.endsWith (.getPath %) ".jar"))
      (map #(JarFile. %))
      (map #(vector (.getName %) (entries %))))))
