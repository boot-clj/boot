(ns boot.jar
  (:require
   [clojure.java.io :as io]
   [boot.util       :as util]
   [boot.filesystem :as fs])
  (:import
   [java.util.zip ZipEntry ZipOutputStream ZipException]
   [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]))

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

(defn update-zip!
  [zipfile old-fs new-fs]
  (with-open [fs (fs/mkjarfs zipfile :create true)]
    (fs/patch! fs old-fs new-fs)))

(defn update-jar!
  [jarfile old-fs new-fs attr main]
  (with-open [fs (fs/mkjarfs jarfile :create true)]
    (fs/patch! fs old-fs new-fs)
    (fs/write! fs (create-manifest main attr) (io/file "META-INF" "MANIFEST.MF"))))
