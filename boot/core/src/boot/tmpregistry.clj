(ns boot.tmpregistry
  (:refer-clojure :exclude [get])
  (:require
    [boot.file       :as file]
    [boot.kahnsort   :as ksort]
    [boot.pod        :as pod]
    [clojure.java.io :as io]
    [clojure.core    :as core]
    [clojure.set     :refer [union intersection difference]])
  (:import
    java.io.File
    java.lang.management.ManagementFactory))

;; helpers

(defn munged-file [dir kw & n]
  (apply io/file dir (-> kw hash (Integer/toString 36)) (remove nil? n)))

(defn delete! [f]
  (let [delete (io/file f)]
    (doseq [child (.listFiles delete)] (delete! child))
    (.delete delete)))

(defn dir-id []
  (apply str
    (-> (->> (.. ManagementFactory getRuntimeMXBean getName)
          (take-while (partial not= \@))
          (apply str)
          Long/parseLong)
      (Long/toString 36))
    (when-not (= 1 @pod/pod-id) ["-" (Long/toString @pod/pod-id 36)])))

(defn mark-delete-me! [dir]
  #(when (.exists dir) (.createNewFile (io/file dir ".delete-me"))))

(defn clean-delete-me! [dir]
  (doseq [f (->> (.listFiles (io/file dir))
              (mapcat #(seq (.listFiles %)))
              (filter #(= ".delete-me" (.getName %))))]
    (delete! (.getParentFile f))))

(defmulti  make-file! (fn [type f] type))
(defmethod make-file! ::file [type f] (doto f delete! (.createNewFile)))
(defmethod make-file! ::dir  [type f] (doto f delete! (.mkdirs)))

;;; tmpregistry interface

(defprotocol ITmpRegistry
  (-init!       [this]                "Initialize temp registry.")
  (-get         [this k]              "Retrieve a temp file or directory.")
  (-unmk!       [this k]              "Remove a temp file or directory.")
  (-mk!         [this type key name]  "Create a temp file or directory.")
  (-add-sync!   [this dest srcs]      "Add a directory which is to be synced.")
  (-sync!       [this]                "Sync directories added with -add-sync!.")
  (-tmpfile?    [this f]              "Is f a file in the tmpregistry?"))

(defn init!     [this]              (doto this -init!))
(defn get       [this key]          (-get this key))
(defn unmk!     [this key]          (doto this (-unmk! key)))
(defn mk!       [this key & [name]] (-mk! this ::file key (or name "file.tmp")))
(defn mkdir!    [this key & [name]] (-mk! this ::dir key name))
(defn add-sync! [this dst & [srcs]] (doto this (-add-sync! dst srcs)))
(defn sync!     [this]              (doto this -sync!))
(defn tmp-file? [this f]            (-tmpfile? this f))

;;; tmpregistry implementation

(defn- persist! [dir initialized? oldreg newreg]
  (let [[o n] (map set [oldreg newreg])
        rmv (difference o n)
        add (difference n o)]
    (locking initialized?
      (when-not @initialized? (delete! dir))
      (reset! initialized? true))
    (doseq [[k v] rmv]
      (delete! (munged-file dir k)))
    (doseq [[k [t _ n]] add]
      (make-file! t (doto (munged-file dir k n) io/make-parents)))))

(defrecord TmpRegistry [dir initialized? reg syncs]
  ITmpRegistry
  (-init! [this]
    (clean-delete-me! (.getParentFile (io/file dir)))
    (pod/add-shutdown-hook! (mark-delete-me! (io/file dir)))
    (add-watch reg ::_ #(persist! dir initialized? %3 %4)))
  (-get [this k]
    (munged-file dir k (nth (@reg k) 2)))
  (-unmk! [this k]
    (swap! reg dissoc k))
  (-mk! [this t k n]
    (swap! reg assoc k [t (gensym) n])
    (-get this k))
  (-add-sync! [this dest srcs]
    (let [srcs (set (map io/file srcs))]
      (swap! syncs update-in [(io/file dest)] #(if % (into % srcs) srcs))))
  (-sync! [this]
    (let [path  #(.getPath %)
          syncs (->> @syncs
                  (reduce-kv #(assoc %1 (path %2) (into #{} (map path %3))) {}))
          dests (set (keys syncs))
          sortd (->> syncs ksort/topo-sort reverse (filter #(contains? dests %)))]
      (assert (or (not (nil? sortd)) (empty? dests))
        "syncs appear to have a cyclic dependency")
      (doseq [dest sortd]
        (apply file/sync! :hash (io/file dest) (map io/file (core/get syncs dest))))))
  (-tmpfile? [this f]
    (when (file/parent? dir f) f)))

(defn registry [dir]
  (TmpRegistry. (io/file dir (dir-id)) (atom false) (atom {}) (atom {})))
