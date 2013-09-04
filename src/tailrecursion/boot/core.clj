(ns tailrecursion.boot.core
  (:require [cemerick.pomegranate           :as pom]
            [clojure.java.io                :as io]
            [clojure.string                 :as string :refer [join]]
            [tailrecursion.boot.tmpregistry :as tmp])
  (:import [java.net URLClassLoader URL]))

;; INTERNAL ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare lb rb)

(defn load-sym [sym]
  (when-let [ns (namespace sym)] (require (symbol ns))) 
  (or (resolve sym) (assert false (format "Can't resolve #'%s." sym))))

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn add-dependencies! [env]
  (let [{deps :dependencies, repos :repositories} env
        deps (mapv (partial exclude ['org.clojure/clojure]) deps)]
    (pom/add-dependencies :coordinates deps :repositories (zipmap repos repos))))

(defn add-directories! [env]
  (when-let [dirs (seq (:directories env))] 
    (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL])) (.setAccessible true))]
      (.invoke meth (ClassLoader/getSystemClassLoader) (object-array (map #(.. (io/file %) toURI toURL) dirs))))))

(defn configure! [old new]
  (when-not (= (:dependencies old) (:dependencies new)) (add-dependencies! new))
  (when-not (= (:directories old) (:directories new)) (add-directories! new)))

;; PUBLIC ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn help-task [boot]
  (let [tasks (map name (remove nil? (sort (keys (:tasks @boot)))))]
    (printf "Usage: boot [task ...]\n") 
    (if (seq tasks)
      (printf "Available tasks: %s.\n" (apply str (interpose ", " tasks)))
      (printf "There are no available tasks.\n"))))

(defn init! [base-env]
  (doto (atom base-env) (add-watch (gensym) (fn [_ _ o n] (configure! o n)))))

(defn prep-next-task! [boot]
  (swap! boot
    (fn [env] 
      (when-let [nxt (first (get-in env [:system :argv]))]
        (let [env  (update-in env [:system :argv] rest)
              tkey (keyword (first nxt))
              task (get-in env [:tasks tkey])
              main {:main (into [(:main task)] (rest nxt))}
              sel  #(select-keys % [:directories :dependencies :repositories])]
          (when tkey (assert task (format "No such task: '%s'" (name tkey))))
          (merge env task main (merge-with into (sel env) (sel task))))))))

(defn run-current-task! [boot]
  (swap! boot
    (fn [env]
      (when-let [[task & args] (:main env)]
        (apply ((if (symbol? task) load-sym eval) task) boot args) 
        (flush) 
        (dissoc env :main)))))

(defn run-next-task! [boot]
  (prep-next-task! boot)
  (run-current-task! boot))

;; because vim paredit gets confused by \[ and \]
(def lb \[)
(def rb \])
