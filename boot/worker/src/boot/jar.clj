(ns boot.jar
  (:require
   [clojure.java.io :as io]
   [boot.pod        :as pod]
   [boot.file       :as file]
   [boot.util       :as util]
   [boot.aether     :as aether])
  (:import
   [java.io File]
   [java.util.zip ZipException]
   [java.util.jar JarFile JarEntry JarOutputStream Manifest Attributes$Name]))

(def dfl-attr
  {"Created-By"  "Tailrecursion Boot Build Tool"
   "Built-By"    (System/getProperty "user.name")
   "Build-Jdk"   (System/getProperty "java.version")})

(defn create-manifest [main ext-attrs]
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

(defn spit-jar! [jarpath files attr main]
  (let [manifest  (create-manifest main attr)
        jarfile   (io/file jarpath)]
    (io/make-parents jarfile)
    (util/info "Writing %s...\n" (.getName jarfile))
    (with-open [s (JarOutputStream. (io/output-stream jarfile) manifest)]
      (doseq [[jarpath srcpath] files :let [f (io/file srcpath)]]
        (let [entry (doto (JarEntry. jarpath) (.setTime (.lastModified f)))]
          (doto s (.putNextEntry entry) (write! (io/input-stream srcpath)) .closeEntry))))))

(defn uber-jar! [jarpath uberpath urls]
  (let [uberfile (io/file uberpath)
        jarfile  (JarFile. (io/file jarpath))
        manifest (.getManifest jarfile)]
    (util/info "Writing %s...\n" (.getName uberfile))
    (with-open [s (JarOutputStream. (io/output-stream uberfile) manifest)]
      (doseq [[jarpath srcurl] (concat (aether/jar-entries jarpath) urls)]
        (when-not (.startsWith jarpath "META-INF/")
          (let [entry (JarEntry. jarpath)]
            (try (doto s (.putNextEntry entry) (write! (io/input-stream srcurl)))
                 (catch Throwable t
                   (if-not (and (instance? ZipException t)
                             (.startsWith (.getMessage t) "duplicate entry:"))
                     (throw t)
                     (println (.getMessage t)))))))))))
