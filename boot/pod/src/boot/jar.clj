(ns boot.jar
  (:require
   [clojure.java.io :as io]
   [boot.pod        :as pod]
   [boot.file       :as file]
   [boot.util       :as util])
  (:import
   [java.io File]
   [java.util.zip ZipEntry ZipOutputStream ZipException]
   [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]))

(def ^:private dfl-attr
  {"Created-By" "Tailrecursion Boot Build Tool"
   "Built-By"   (System/getProperty "user.name")
   "Build-Jdk"  (System/getProperty "java.version")})

(defn- create-manifest [main ext-attrs]
  (let [extra-attr  (merge-with into dfl-attr ext-attrs)
        manifest    (Manifest.) 
        attributes  (.getMainAttributes manifest)]
    (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
    (when-let [m (and main (.replaceAll (str main) "-" "_"))]
      (.put attributes Attributes$Name/MAIN_CLASS m))
    (doseq [[k v] extra-attr] (.put attributes (Attributes$Name. (name k)) v))
    manifest))

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
        (let [entry (doto (JarEntry. jarpath) (.setTime (.lastModified f)))]
          (try
            (doseq [d (parents jarpath)]
              (swap! dirs conj d)
              (doto s (.putNextEntry (JarEntry. d)) .closeEntry))
            (doto s (.putNextEntry entry) (write! (io/input-stream srcpath)) .closeEntry)
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
