(ns tailrecursion.boot.tmpregistry
  (:refer-clojure :exclude [get])
  (:require
    [tailrecursion.boot.file  :as f]
    [clojure.java.io          :as io]
    [clojure.string           :as str]
    [clojure.core             :as core]
    [clojure.set              :refer [union intersection difference]])
  (:import
    java.io.File
    java.lang.management.ManagementFactory))

(defn delete! [f]
  (let [delete (io/file f)]
    (doseq [child (.listFiles delete)] (delete! child))
    (.delete delete)))

(defn pid! []
  (->> (.. ManagementFactory getRuntimeMXBean getName) 
    (take-while (partial not= \@))
    (apply str)))

(defn add-shutdown-hook! [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

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
(defn mkdir!    [this key & [name]] (-mk! this ::dir key (or name "dir.tmp")))
(defn add-sync! [this dst & [srcs]] (doto this (-add-sync! dst srcs)))
(defn sync!     [this]              (doto this -sync!))
(defn tmpfile?  [this f]            (-tmpfile? this f))

;;; tmpregistry implementation

(defn- persist! [dir initialized? oldreg newreg]
  (let [[o n] (map set [oldreg newreg])
        rmv (difference o n)
        add (difference n o)]
    (locking initialized?
      (when-not @initialized? (delete! dir))
      (reset! initialized? true))
    (doseq [[k v] rmv]
      (delete! (io/file dir (munge k))))
    (doseq [[k [t _ n]] add]
      (make-file! t (doto (io/file dir (munge k) n) io/make-parents)))))

(defrecord TmpRegistry [dir initialized? reg syncs]
  ITmpRegistry
  (-init! [this]
    (clean-delete-me! (.getParentFile (io/file dir)))
    (add-shutdown-hook! #(.createNewFile (io/file dir ".delete-me")))
    (add-watch reg ::_ #(persist! dir initialized? %3 %4)))
  (-get [this k]
    (io/file dir (munge k) (nth (@reg k) 2)))
  (-unmk! [this k]
    (swap! reg dissoc k))
  (-mk! [this t k n]
    (swap! reg assoc k [t (gensym) n])
    (-get this k))
  (-add-sync! [this dest srcs]
    (let [srcs (set (map io/file srcs))]
      (swap! syncs update-in [(io/file dest)] #(if % (into % srcs) srcs))))
  (-sync! [this]
    (doseq [[dest srcs] @syncs] (apply f/sync :hash dest srcs)))
  (-tmpfile? [this f]
    (when (f/parent? dir f) f)))

(defn registry [dir]
  (TmpRegistry. (io/file dir (pid!)) (atom false) (atom {}) (atom {})))
