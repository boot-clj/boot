(ns tailrecursion.boot
  (:require [cemerick.pomegranate           :as pom]
            [clojure.java.io                :as io]
            [tailrecursion.boot.tmpregistry :as tmp])
  (:import java.lang.management.ManagementFactory
           [java.net URLClassLoader URL])
  (:gen-class))

(def base-env
  {:project      nil
   :version      nil
   :dependencies #{}
   :directories  #{}
   :repositories #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"}
   :system       {:jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                  :bootfile (io/file (System/getProperty "user.dir") "boot.clj")
                  :cwd      (io/file (System/getProperty "user.dir"))}
   :main         nil})

(def env (atom base-env))

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn add-artifacts! []
  (let [{deps :dependencies, repos :repositories} @env
        deps (mapv (partial exclude ['org.clojure/clojure]) deps)]
    (pom/add-dependencies :coordinates deps :repositories (zipmap repos repos))))

(defn add-directories! []
  (when-let [dirs (seq (:directories @env))] 
    (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL])) (.setAccessible true))]
      (.invoke meth (ClassLoader/getSystemClassLoader) (object-array (map #(.. (io/file %) toURI toURL) dirs))))))

(defn -main [& args]
  (swap! env assoc-in [:system :argv] args)
  (let [f (io/file (get-in @env [:system :bootfile]))]
    (assert (.exists f) (format "File '%s' not found." f))
    (let [cfg (read-string (slurp f))]
      (swap! env merge cfg)
      (add-artifacts!)
      (add-directories!)
      (let [m (:main @env)]
        (assert (and (symbol? m) (namespace m)) "The :main value must be a namespaced symbol.")
        (require (symbol (namespace m))) 
        (let [g (resolve m)]
          (assert g (format "Can't resolve #'%s." m)) 
          (swap! env merge (tmp/create-registry!)) 
          (g env))))))
