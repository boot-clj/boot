(ns tailrecursion.boot.core
  (:require [cemerick.pomegranate           :as pom]
            [clojure.java.io                :as io]
            [clojure.set                    :refer [difference]]
            [clojure.string                 :refer [join]]
            [clojure.pprint                 :refer [pprint]]
            [tailrecursion.boot.tmpregistry :as tmp])
  (:import [java.net URLClassLoader URL]))

;; INTERNAL ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro guard [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defn load-sym [sym]
  (when-let [ns (and sym (namespace sym))] (require (symbol ns)))
  (or (resolve sym) (assert false (format "Can't resolve #'%s." sym))))

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn add-dependencies! [deps repos]
  (let [deps (mapv (partial exclude ['org.clojure/clojure]) deps)]
    (pom/add-dependencies :coordinates deps :repositories (zipmap repos repos))))

(defn add-directories! [dirs]
  (when (seq dirs)
    (let [meth  (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
                  (.setAccessible true))
          cldr  (ClassLoader/getSystemClassLoader)
          urls  (->> dirs (map io/file) (filter #(.exists %)) (map #(.. % toURI toURL)))]
      (doseq [url urls] (.invoke meth cldr (object-array [url]))))))

(defn configure! [old new]
  (let [[nd od] [(:dependencies new) (:dependencies old)] 
        [ns os] [(:src-paths new) (:src-paths old)]]
    (when-not (= nd od) (add-dependencies! nd (:repositories new)))
    (when-not (= ns os) (add-directories! (difference ns os)))))

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
  (let [m     (or (:main task) '[tailrecursion.boot.core.task/nop]) 
        main  {:main (if (seq args) (into [(first m)] args) m)}
        sel   #(select-keys % [:src-paths :dependencies :repositories])
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
    (when-let [[tfd & args] (first (get-in @boot [:system :argv]))]
      (swap! boot
        (fn [env] 
          (when-let [[tfn & args] (first (get-in env [:system :argv]))]
            (if-let [task (get-in env [:tasks (keyword tfn)])]
              (prep-task (update-in env [:system :argv] rest) task args)
              (assert false (format "No such task: '%s'" tfn)))))) 
      (swap! boot require-tasks))))

(defn get-current-middleware! [boot]
  (locking boot
    (when-let [[task & args] (:main @boot)]
      (swap! boot dissoc :main)
      (apply ((if (symbol? task) load-sym eval) task) boot args))))

(defn get-next-middleware! [boot]
  (locking boot
    (when (prep-next-task! boot) (get-current-middleware! boot))))

(defn create-app! [boot]
  (let [tmp   (get-in @boot [:system :tmpregistry])
        run!  #(get-next-middleware! boot)
        tasks (loop [task (run!), tasks []]
                (if task (recur (run!) (conj tasks task)) tasks))]
    ((apply comp tasks) #(do (tmp/sync! tmp) (flush) %))))
