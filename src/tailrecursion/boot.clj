(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [clojure.java.io :as io]
            [tailrecursion.boot.tmpregistry :as tmp]
            [tailrecursion.boot.dispatch :as dispatch])
  (:import java.lang.management.ManagementFactory
           [java.net URLClassLoader URL])
  (:gen-class))

(def base-env
  {:project      nil
   :version      nil
   :dependencies #{}
   :directories  #{}
   :repositories #{}
   :system       {:jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                  :bootfile (io/file (System/getProperty "user.dir") "boot.clj")
                  :cwd      (io/file (System/getProperty "user.dir"))}
   :installed    #{}})

(def env (atom base-env))

(def ^:dynamic *default-repositories*
  #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"})

(defn find-idx [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (find-idx coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn install [{:keys [dependencies repositories]}]
  (let [deps      (mapv (partial exclude ['org.clojure/clojure]) dependencies)
        repos     (into *default-repositories* repositories)
        installed (pom/add-dependencies :coordinates deps :repositories (zipmap repos repos))
        new-deps  {:dependencies deps :repositories repos :installed installed}]
    (swap! env (partial merge-with into) new-deps)))

(defn add [dirs]
  (when (seq dirs)
    (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
                 (.setAccessible true))]
      (.invoke meth (ClassLoader/getSystemClassLoader)
               (object-array (map #(.. (io/file %) toURI toURL) dirs)))
      (swap! env update-in [:directories] into dirs))))

(defn configure [cfg]
  (install cfg)
  (add (get cfg :directories))
  (swap! env merge cfg)
  `(quote ~cfg))

(defn -main [& args]
  (tmp/create-registry!)
  (binding [*command-line-args* args
            *ns* (create-ns 'user)
            *data-readers* {'boot/configuration #'configure}]
    (alias 'tmp 'tailrecursion.boot.tmpregistry)
    (alias 'boot 'tailrecursion.boot)
    (-> @env (get-in [:system :bootfile]) .getAbsolutePath load-file)))
