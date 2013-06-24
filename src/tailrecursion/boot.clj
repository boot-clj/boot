(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :refer [maven-central]])
  (:import java.lang.management.ManagementFactory
           java.io.File)
  (:gen-class))

(defn find-idx [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude-clojure [coordinate]
  (if-let [idx (find-idx coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (conj exclusions 'org.clojure/clojure)))
    (into coordinate [:exclusions ['org.clojure/clojure]])))

(def installed (atom {}))

(def ^:dynamic *default-repositories*
  {"maven" "http://repo1.maven.org/maven2/"
   "clojars" "http://clojars.org/repo"})

(defn install
  [{:keys [coordinates repositories]}]
  (swap! installed merge
   (pom/add-dependencies
    :coordinates (mapv exclude-clojure coordinates)
    :repositories (merge *default-repositories* repositories))))

(defn make-request []
  {:installed @installed
   :cli-args *command-line-args*
   :jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
   :cwd (System/getProperty "user.dir")})

(defn -main [& args]
  (binding [*command-line-args* args
            *ns* (create-ns 'user)]
    (alias 'boot 'tailrecursion.boot)
    (load-file "boot.clj")))
