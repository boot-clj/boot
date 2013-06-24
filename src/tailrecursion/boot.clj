(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :refer [maven-central]]
            [clojure.java.io :as io])
  (:import java.lang.management.ManagementFactory
           [java.net URLClassLoader URL])
  (:gen-class))

(defn find-idx [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude-clojure [coordinate]
  (if-let [idx (find-idx coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (conj exclusions 'org.clojure/clojure)))
    (into coordinate [:exclusions ['org.clojure/clojure]])))

(def classpath
  (atom {:dependencies {} :directories #{}}))

(def ^:dynamic *default-repositories*
  {"maven" "http://repo1.maven.org/maven2/"
   "clojars" "http://clojars.org/repo"})

(defn install
  [{:keys [coordinates repositories]}]
  (let [deps (pom/add-dependencies
              :coordinates (mapv exclude-clojure coordinates)
              :repositories (merge *default-repositories* repositories))]
    (swap! classpath update-in [:dependencies] merge deps)))

(defn add
  [dirs]
  (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
               (.setAccessible true))]
    (.invoke meth (ClassLoader/getSystemClassLoader)
             (into-array Object (map #(.. (io/file %) toURI toURL) dirs)))
    (swap! classpath update-in [:directories] into dirs)))

(defn make-request []
  {:classpath @classpath
   :cli-args *command-line-args*
   :jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
   :cwd (System/getProperty "user.dir")})

(defn -main [& args]
  (binding [*command-line-args* args
            *ns* (create-ns 'user)]
    (alias 'boot 'tailrecursion.boot)
    (load-file "boot.clj")))
