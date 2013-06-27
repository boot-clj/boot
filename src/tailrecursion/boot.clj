(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :refer [maven-central]]
            [clojure.java.io :as io]
            [tailrecursion.boot.tmpregistry :as tmp]
            [tailrecursion.boot.dispatch :as dispatch]
            [clojure.core :as core])
  (:refer-clojure :exclude [get])
  (:import java.lang.management.ManagementFactory
           [java.net URLClassLoader URL])
  (:gen-class))

(def base-env
  {:boot {:dependencies #{}
          :directories #{}
          :repositories #{}
          :system {:jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                   :cwd (System/getProperty "user.dir")}
          :pomegranate {:installed #{}}}})

(def env (atom base-env))

(def ^:dynamic *default-repositories*
  #{"http://repo1.maven.org/maven2/"
    "http://clojars.org/repo/"})

(defn find-idx [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (find-idx coordinate :exclusions)]
    (let [exclusions (core/get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn install [{:keys [coordinates repositories]}]
  (let [deps (mapv (partial exclude ['org.clojure/clojure]) coordinates)
        repos (into *default-repositories* repositories)
        installed (pom/add-dependencies
                   :coordinates deps
                   :repositories (apply hash-map (mapcat (partial repeat 2) repos)))]
    (swap! env (partial merge-with into)
           {:boot {:dependencies deps
                   :repositories repos
                   :pomegranate {:installed installed}}})))

(defn add [dirs]
  (when (seq dirs)
    (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
                 (.setAccessible true))]
      (.invoke meth (ClassLoader/getSystemClassLoader)
               (object-array (map #(.. (io/file %) toURI toURL) dirs)))
      (swap! env update-in [:boot :directories] into dirs))))

(defn configure [{:keys [boot] :as cfg}]
  (install boot)
  (add (core/get boot :directories))
  (swap! env merge (dissoc cfg :boot))
  `(quote ~cfg))

(defn dispatch-cli []
  (dispatch/dispatch-cli @env *command-line-args*))

(defn -main [& args]
  (tmp/create-registry!)
  (binding [*command-line-args* args
            *ns* (create-ns 'user)
            *data-readers* {'boot/configuration #'configure}]
    (alias 'tmp 'tailrecursion.boot.tmpregistry)
    (alias 'boot 'tailrecursion.boot)
    (load-file (or (System/getenv "BOOT") "boot.clj"))))
