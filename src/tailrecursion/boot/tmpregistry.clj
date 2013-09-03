(ns tailrecursion.boot.tmpregistry
  (:require [clojure.java.io :as io]
            [clojure.set :refer [union intersection difference]]
            [clojure.string :as str]
            [clojure.core :as core])
  (:refer-clojure :exclude [get])
  (:import java.io.File))

(defn delete! [f]
  (let [delete (io/file f)]
    (doseq [child (.listFiles delete)] (delete! child))
    (.delete delete)))

(defmulti  make-file! (fn [type f] type))
(defmethod make-file! ::file [type f] (doto f (.createNewFile)))
(defmethod make-file! ::dir  [type f] (doto f (.mkdirs)))

;;; tmpregistry interface

(defprotocol ITmpRegistry
  (-init!     [this]                "Initialize temp registry.")
  (-get       [this k]              "Retrieve a temp file or directory.")
  (-unmk!     [this k]              "Remove a temp file or directory.")
  (-mk!       [this type key name]  "Create a temp file or directory."))

(defn init!   [this]              (-init! this))
(defn get     [this key]          (-get this key))
(defn unmk!   [this key]          (-unmk! this key))
(defn mk!     [this key & [name]] (-mk! this ::file key (or name "file.tmp")))
(defn mkdir!  [this key & [name]] (-mk! this ::dir key (or name "dir.tmp")))

;;; tmpregistry implementation

(defn- persist! [dir oldreg newreg]
  (let [[o n] (map set [oldreg newreg])
        rmv (difference o n)
        add (difference n o)]
    (doseq [[k v] rmv]
      (delete! (io/file dir (munge k))))
    (doseq [[k [t _ n]] add]
      (make-file! t (doto (io/file dir (munge k) n) io/make-parents)))))

(defrecord TmpRegistry [dir reg]
  ITmpRegistry
  (-init! [this]
    (delete! dir)
    (add-watch reg (gensym) (fn [_ _ old new] (persist! dir old new)))
    this)
  (-get [this k]
    (io/file dir (munge k) (nth (@reg k) 2)))
  (-unmk! [this k]
    (swap! reg dissoc k)
    this)
  (-mk! [this t k n]
    (swap! reg assoc k [t (gensym) n])
    (-get this k)))

(defn registry [dir]
  (TmpRegistry. (io/file dir) (atom {})))
