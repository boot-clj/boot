(ns boot.filesystem
  (:require
    [clojure.java.io :as io]
    [boot.file       :as file]
    [boot.util       :as util]
    [boot.tmpdir     :as tmpd])
  (:import
   [java.net URI]
   [java.io File]
   [java.nio.file.attribute FileAttribute FileTime]
   [java.util.zip ZipEntry ZipOutputStream ZipException]
   [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]
   [java.nio.file Files FileSystems StandardCopyOption StandardOpenOption]))

(def copy-opts (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
(def open-opts (into-array StandardOpenOption [StandardOpenOption/CREATE]))

(defn mkfs
  [^File rootdir]
  (.toPath rootdir))

(defn mkjarfs
  [^File jarfile]
  (io/make-parents jarfile)
  (FileSystems/newFileSystem
    (URI. (str "jar:" (.toURI jarfile))) {"create" "true"}))

(defn mkpath
  [fs file]
  (if (instance? java.nio.file.Path fs)
    (.resolve fs (.toPath file))
    (let [[seg & segs] (file/split-path file)]
      (.getPath fs seg (into-array String segs)))))

(defn mkparents!
  [path]
  (when-let [p (.getParent path)]
    (Files/createDirectories p (into-array FileAttribute []))))

(defn touch!
  [fs path time]
  (let [dst (mkpath fs (io/file path))]
    (util/dbug "Filesystem: touching %s...\n" path)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn copy!
  [fs path file time]
  (let [src (.toPath file)
        dst (mkpath fs (io/file path))]
    (util/dbug "Filesystem: copying %s...\n" path)
    (Files/copy src (doto dst mkparents!) copy-opts)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn link!
  [fs path file]
  (let [src (.toPath file)
        dst (mkpath fs (io/file path))]
    (util/dbug "Filesystem: linking %s...\n" path)
    (Files/deleteIfExists dst)
    (Files/createLink (doto dst mkparents!) src)))

(defn delete!
  [fs path]
  (util/dbug "Filesystem: deleting %s...\n" path)
  (Files/delete (mkpath fs (io/file path))))

(defn write!
  [fs writer path]
  (let [dst (mkpath fs (io/file path))]
    (mkparents! dst)
    (with-open [os (Files/newOutputStream dst open-opts)]
      (util/dbug "Filesystem: writing %s...\n" path)
      (.write writer os))))

(defn patch!
  [fs old-fs new-fs & {:keys [link]}]
  (doseq [[op & [arg1 arg2]] (tmpd/patch old-fs new-fs :link link)]
    (let [[p1 f1 t1] ((juxt tmpd/path tmpd/file tmpd/time) arg1)
          [p2 f2 t2] (when arg2 ((juxt tmpd/path tmpd/file tmpd/time) arg2))]
      (case op
        :delete (delete! fs p1)
        :write  (copy!   fs p1 f1 t1)
        :link   (link!   fs p1 f1)
        :touch  (touch!  fs p1 t1)))))
