(ns boot.jar
  (:require
   [clojure.java.io :as io]
   [clojure.set     :as set]
   [boot.pod        :as pod]
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

(defn- create-manifest [main ext-attrs]
  (util/with-let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))))

(defn- write! [stream file]
  (let [buf (byte-array 1024)]
    (with-open [in (io/input-stream file)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write stream buf 0 n)
          (recur (.read in buf)))))))

(defn dupe? [t]
  (and (instance? ZipException t)
       (.startsWith (.getMessage t) "duplicate entry:")))

(defn jarentry [path f & [dir?]]
  (doto (JarEntry. (str (.replaceAll path "\\\\" "/") (when dir? "/")))
    (.setTime (.lastModified f))))

(defn spit-jar! [jarpath files attr main]
  (let [manifest  (create-manifest main attr)
        jarfile   (io/file jarpath)
        dirs      (atom #{})
        parents*  #(iterate (comp (memfn getParent) io/file) %)
        parents   #(->> % parents* (drop 1)
                     (take-while (complement empty?))
                     (remove (partial contains? @dirs)))]
    (io/make-parents jarfile)
    (with-open [s (JarOutputStream. (io/output-stream jarfile) manifest)]
      (doseq [[jarpath srcpath] files :let [f (io/file srcpath)]]
        (let [e (jarentry jarpath f)]
          (try
            (doseq [d (parents jarpath) :let [f (io/file d)]]
              (swap! dirs conj d)
              (doto s (.putNextEntry (jarentry d f true)) .closeEntry))
            (doto s (.putNextEntry e) (write! (io/input-stream srcpath)) .closeEntry)
            (catch Throwable t
              (if-not (dupe? t) (throw t) (util/warn "%s\n" (.getMessage t))))))))))

(defn spit-zip! [zippath files]
  (let [zipfile (io/file zippath)]
    (io/make-parents zipfile)
    (with-open [s (ZipOutputStream. (io/output-stream zipfile))]
      (doseq [[zippath srcpath] files :let [f (io/file srcpath)]]
        (when-not (.isDirectory f)
          (let [entry (doto (ZipEntry. zippath) (.setTime (.lastModified f)))]
            (try
              (doto s (.putNextEntry entry) (write! (io/input-stream srcpath)) .closeEntry)
              (catch Throwable t
                (if-not (dupe? t) (throw t) (util/warn "%s\n" (.getMessage t)))))))))))

;; new jar fns ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private copy-opts
  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))

(def ^:private open-opts
  (into-array StandardOpenOption [StandardOpenOption/CREATE]))

(defn- jar-filesystem
  [jarfile]
  (io/make-parents jarfile)
  (FileSystems/newFileSystem
    (URI. (str "jar:" (.toURI jarfile))) {"create" "true"}))

(defn- filesystem-path
  [fs file]
  (let [[seg & segs] (file/split-path file)]
    (.getPath fs seg (into-array String segs))))

(defn- mkparents
  [path]
  (when-let [p (.getParent path)]
    (Files/createDirectories p (into-array FileAttribute []))))

(defn- filesystem-set-time
  [fs path time]
  (let [dst (filesystem-path fs (io/file path))]
    (util/dbug "Updating mod time for %s in zip/jar...\n" path)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn- filesystem-write
  [fs path file time]
  (let [src (.toPath file)
        dst (filesystem-path fs (io/file path))]
    (util/dbug "Adding %s to zip/jar...\n" path)
    (Files/copy src (doto dst mkparents) copy-opts)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn- filesystem-delete
  [fs path]
  (util/dbug "Deleting %s from zip/jar...\n" path)
  (Files/delete (filesystem-path fs (io/file path))))

(defn- filesystem-manifest
  [fs manifest]
  (let [dst (filesystem-path fs (io/file "META-INF" "MANIFEST.MF"))]
    (mkparents dst)
    (with-open [os (Files/newOutputStream dst open-opts)]
      (util/dbug "Adding MANIFEST.MF to jar...\n")
      (.write manifest os))))

(defn filesystem-patch!
  [fs old-fs new-fs]
  (doseq [[op & [arg1 arg2]] (tmpd/patch old-fs new-fs)]
    (let [[p1 f1 t1] ((juxt tmpd/path tmpd/file tmpd/time) arg1)
          [p2 f2 t2] (when arg2 ((juxt tmpd/path tmpd/file tmpd/time) arg2))]
      (case op
        :delete   (filesystem-delete   fs p1)
        :write    (filesystem-write    fs p1 f1 t1)
        :set-time (filesystem-set-time fs p1 t1)))))

(defn update-zip!
  [zipfile old-fs new-fs]
  (with-open [fs (jar-filesystem zipfile)]
    (filesystem-patch! fs old-fs new-fs)))

(defn update-jar!
  [jarfile old-fs new-fs attr main]
  (with-open [fs (jar-filesystem jarfile)]
    (filesystem-manifest fs (create-manifest main attr))
    (filesystem-patch! fs old-fs new-fs)))
