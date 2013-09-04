(ns tailrecursion.boot.core
  (:require [cemerick.pomegranate           :as pom]
            [clojure.java.io                :as io]
            [tailrecursion.boot.tmpregistry :as tmp])
  (:import [java.net URLClassLoader URL]))

;; INTERNAL ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn usage-task [boot]
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
      (let [args (get-in env [:system :argv])
            tkey (keyword (first args))
            task (get-in env [:tasks tkey])
            sel  #(select-keys % [:directories :dependencies :repositories]) 
            deps (merge-with into (sel env) (sel task))] 
        (if tkey (assert task (format "No such task: '%s'" (name tkey))))
        (update-in (merge env task deps) [:system :argv] rest)))))

(defn run-current-task! [boot]
  (if-let [m (:main @boot)]
    (do
      (cond (symbol? m) ((load-sym m) boot) (seq? m) ((eval m) boot)) 
      (swap! boot dissoc :main) 
      (flush) 
      ::okay)))

(defn run-next-task! [boot]
  (prep-next-task! boot)
  (run-current-task! boot))
