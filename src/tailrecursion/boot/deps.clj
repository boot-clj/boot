;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

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

(defn deps [env]
  (let [{repos :repositories coords :dependencies} @env]
    (->> (resolve-dependencies :repositories (zipmap repos repos) :coordinates coords)
      (topo-sort)
      (map #(:file (meta %)))
      (filter #(.endsWith (.getPath %) ".jar"))
      (map #(JarFile. %))
      (map #(vector (.getName %) (entries %))))))
