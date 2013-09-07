(ns tailrecursion.boot.core
  (:require [cemerick.pomegranate           :as pom]
            [clojure.java.io                :as io]
            [clojure.string                 :refer [join]]
            [clojure.pprint                 :refer [pprint]]
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

(defn require-task [tasks [ns & {:keys [refer as]}]]
  {:pre [(symbol? ns)
         (or as refer)
         (or (nil? as) (symbol? as))
         (or (nil? refer) (= :all refer) (vector? refer))]}
  (require ns)
  (let [pub (-> (->> (ns-publics ns) (map (fn [[k v]] [k (meta v)]))) 
                (->> (filter (comp ::task second)) (map first))) 
        mk  (fn [sym] {:main [(symbol (str ns) (str sym))]})
        r   (when refer
              (->> pub
                (filter #(or (= :all refer) (contains? (set refer) %)))
                (map #((juxt keyword mk) %)))) 
        a   (when as
              (map #((juxt (comp (partial keyword (str as)) str) mk) %) pub))] 
    (merge (reduce into {} [r a]) tasks)))

(defn require-tasks [env]
  (assoc env :tasks (reduce require-task (:tasks env) (:require-tasks env))))

(defn prep-task [env task args]
  (let [m     (:main task)
        main  {:main (if (seq args) (into [(first m)] args) m)}
        sel   #(select-keys % [:directories :dependencies :repositories])
        sel2  #(select-keys % [:require-tasks])
        mvn   (merge-with into (sel env) (sel task))
        req   (merge-with into (sel2 env) (sel2 task))]
    (merge env task main mvn req)))

;; BOOT API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro deftask [name & args]
  `(defn ~(with-meta name {::task true}) ~@args))

(defn make-event
  ([]       {:id (gensym), :time (System/currentTimeMillis)})
  ([event]  (merge event (make-event))))

(defn init! [env]
  (doto (atom env) (add-watch ::_ #(configure! %3 %4))))

(defn prep-next-task! [boot]
  (locking boot
    (swap! boot
      (fn [env] 
        (when-let [[tfn & args] (first (get-in env [:system :argv]))]
          (if-let [task (get-in env [:tasks (keyword tfn)])]
            (prep-task (update-in env [:system :argv] rest) task args)
            (assert false (format "No such task: '%s'" tfn)))))) 
    (swap! boot require-tasks)))

(defn run-current-task! [boot]
  (locking boot
    (when-let [[task & args] (:main @boot)]
      (swap! boot dissoc :main)
      (apply ((if (symbol? task) load-sym eval) task) boot args))))

(defn run-next-task! [boot]
  (locking boot
    (prep-next-task! boot) 
    (run-current-task! boot)))

(defn compose-tasks! [boot]
  (loop [task (run-next-task! boot), stack #(do (flush) %)]
    (if-not task stack (recur (run-next-task! boot) (task stack)))))
