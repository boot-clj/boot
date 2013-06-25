(ns tailrecursion.boot.tmpregistry
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.core :as core])
  (:refer-clojure :exclude [get])
  (:import java.io.File))

;;; file stuff

(def ^:dynamic *basedir* (File. "."))

(defn append-path [f parts]
  (apply str
         (.getAbsolutePath (io/file f))
         (interleave (repeat File/separator) parts)))

(defn delete! [f]
  (let [delete (io/file f)]
    (doseq [child (.listFiles delete)] (delete! child))
    (.delete delete)))

(defn make-parents! [f]
  (.. (io/file f) getParentFile mkdirs))

;;; registry setup/teardown

(def registries (atom {}))

(defn ^File registry-dir []
  (let [relative ".boot/tmp"]
    (io/file (append-path *basedir* (str/split relative #"/")))))

(defn destroy-registry! []
  (let [dir (registry-dir)]
    (swap! registries dissoc dir)
    (delete! dir)))

(defn create-registry! []
  (destroy-registry!)
  (let [dir (registry-dir)]
    (swap! registries assoc-in [dir] {})
    (doto dir (.mkdirs))))

(defn ^File make-file [k name]
  {:pre [(or (symbol? k) (keyword? k) (string? k))]}
  (let [reg (registry-dir)
        tmp (io/file (append-path reg [(munge k) name]))]
    (get-in (swap! registries assoc-in [reg k] tmp) [reg k])))

;;; obtaining, deleting tmp files from a registry

(defn mk [k & [name]]
  (doto (make-file k (or name (str (gensym "file") ".tmp")))
    make-parents!
    (.createNewFile)
    (.setLastModified (System/currentTimeMillis))))

(defn mkdir [k & [name]]
  (doto (make-file k (or name (str (gensym "dir"))))
    delete!
    (.mkdirs)))

(defn unmk [k]
  (let [reg (registry-dir)]
    (swap! registries update-in [reg] dissoc k)
    (delete! (append-path reg [(munge k)]))))

(defn get [k]
  (let [reg (registry-dir)]
    (or (get-in @registries [reg k])
        (throw (IllegalArgumentException.
                (format "No temp file for key %s in registry at %s." k reg))))))

(comment
  ;; by default, the registry goes in (File. "."). can be set with *basedir*.
  (create-registry!);=> recursively deletes and creates: #<File /home/alan/./.boot/tmp>
  (mk ::foo)        ;=> #<File /home/alan/./.boot/tmp/_COLON_tmpregistry_SLASH_foo/file745.tmp>
  (mk ::file "a.b") ;=> #<File /home/alan/./.boot/tmp/_COLON_tmpregistry_SLASH_file/a.b>
  (mkdir ::out)     ;=> #<File /home/alan/./.boot/tmp/_COLON_tmpregistry_SLASH_out/dir750>
  (get ::out)       ;=> #<File /home/alan/./.boot/tmp/_COLON_tmpregistry_SLASH_out/dir750>
  (unmk ::out)
  (destroy-registry!)   ;=> true (~/.boot/tmp was recursively deleted, optional)
)
