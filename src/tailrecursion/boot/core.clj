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

(defn dummy-task [boot]
  (printf "Usage: boot [task1 [task1 ...]]\n")
  (printf "Available tasks: %s.\n"
          (apply str (interpose ", " (map name (keys (:tasks @boot)))))))

(defn init! [base-env]
  (doto (atom base-env) (add-watch (gensym) (fn [_ _ o n] (configure! o n)))))

(defn prep-next-task! [boot & [spec]]
  (swap! boot
    (fn [env] 
      (let [spec (or spec env)
            args (get-in env [:system :argv])]
        (if-let [task-key (keyword (first args))]
          (let [task (get-in spec [:tasks task-key])
                argv (if task (rest args) args)
                sel  #(select-keys % [:directories :dependencies :repositories])
                deps (merge-with into (sel spec) (sel task))]
            (assoc-in (merge env spec task deps) [:system :argv] argv))
          (merge env spec))))))

(defn run-current-task! [boot]
  (when-let [m (:main @boot)]
    (cond (symbol? m) ((load-sym m) boot) (seq? m) ((eval m) boot))
    (swap! boot dissoc :main)
    :ok))

(defn run-next-task! [boot & [spec]]
  (prep-next-task! boot spec)
  (run-current-task! boot))
