(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :refer [maven-central]]
            [clojure.java.io :as io]
            [tailrecursion.boot.tmpregistry :as tmp]
            [clojure.core :as core])
  (:refer-clojure :exclude [get])
  (:import java.lang.management.ManagementFactory
           [java.net URLClassLoader URL])
  (:gen-class))

(def boot-version "1.5.1-SNAPSHOT")

(defn find-idx [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (find-idx coordinate :exclusions)]
    (let [exclusions (core/get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(def env
  (atom {:dependencies {} :directories #{}, :boot-version nil}))

(def ^:dynamic *default-repositories*
  {"maven" "http://repo1.maven.org/maven2/"
   "clojars" "http://clojars.org/repo"})

(defn install [{:keys [coordinates repositories]}]
  (let [deps (pom/add-dependencies
              :coordinates (mapv (partial exclude ['org.clojure/clojure]) coordinates)
              :repositories (merge *default-repositories* repositories))]
    (swap! env update-in [:dependencies] merge deps)))

(defn add [dirs]
  (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
               (.setAccessible true))]
    (.invoke meth (ClassLoader/getSystemClassLoader)
             (object-array (map #(.. (io/file %) toURI toURL) dirs)))
    (swap! env update-in [:directories] into dirs)))

(defn version [string]
  (if-not (= string boot-version)
    (throw (IllegalArgumentException.
            (format "boot.clj specifies boot %s; using %s." string boot-version))))
  (do (swap! env assoc-in [:boot-version] boot-version) boot-version))

(defn make-request []
  (let [env @env]
    {:classpath (select-keys env [:dependencies :directories])
     :boot-version (or (:boot-version env)
                       (throw (IllegalArgumentException.
                               "no #boot/version specified in boot.clj.")))
     :cli-args *command-line-args*
     :jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
     :cwd (System/getProperty "user.dir")}))

(defn -main [& args]
  (tmp/create-registry!)
  (binding [*command-line-args* args
            *ns* (create-ns 'user)
            *data-readers* {'boot/version #'version}]
    (alias 'tmp 'tailrecursion.boot.tmpregistry)
    (alias 'boot 'tailrecursion.boot)
    (load-file "boot.clj")))
