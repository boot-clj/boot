(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :refer [maven-central]]))

(defn find-idx [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude-clojure [coordinate]
  (if-let [idx (find-idx coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (conj exclusions 'org.clojure/clojure)))
    (into coordinate [:exclusions ['org.clojure/clojure]])))

(defn install
  [{:keys [coordinates repositories]}]
  (pom/add-dependencies :coordinates (mapv exclude-clojure coordinates)
                        :repositories (->> repositories
                                           (mapcat (partial repeat 2))
                                           (apply hash-map))))

(defn read-dependencies
  [depmap]
  `(install '~depmap))

(def ^:dynamic *args*)

(defn -main [& args]
  (binding [*data-readers* {'dependencies #'read-dependencies}
            *ns* (create-ns 'user)
            *args* args]
    (alias 'boot 'tailrecursion.boot)
    (load-file "boot.clj")))

