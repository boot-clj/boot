(ns tailrecursion.boot.middleware.jar
  (:import
    [java.io File]
    [java.util.jar JarOutputStream JarEntry Manifest Attributes Attributes$Name])
  (:require
    [clojure.set                    :refer [union]]
    [clojure.string                 :refer [split join]]
    [clojure.java.io                :refer [input-stream output-stream file make-parents]]
    [tailrecursion.boot.tmpregistry :refer [mk mkdir exists? unmk]]))

(def dfl-opts
  {:resources []
   :manifest  nil
   :main      nil})

(def dfl-manifest
  {"Created-By"  "boot"
   "Built-By"    (System/getProperty "user.name")
   "Build-Jdk"   (System/getProperty "java.version")})

(defn- make-manifest [main extra-attributes]
  (let [extra-attr  (merge-with into dfl-manifest extra-attributes)
        manifest    (Manifest.)
        attributes  (.getMainAttributes manifest)]
    (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
    (when-let [m (and main (.replaceAll (str main) "-" "_"))] 
      (.put attributes Attributes$Name/MAIN_CLASS m))
    (doseq [[k v] extra-attr] (.put attributes (Attributes$Name. k) v))
    manifest))

(defn- write! [^JarOutputStream target ^File src]
  (let [buf (byte-array 1024)] 
    (with-open [in (input-stream src)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write target buf 0 n)
          (recur (.read in buf)))))))

(defn- add! [^JarOutputStream target ^File base ^File src]
  (let [rel #(.getPath (.relativize (.toURI %1) (.toURI %2))) 
        ent #(doto (JarEntry. (rel %1 %2)) (.setTime (.lastModified %2)))]
    (if (.isDirectory src)
      (doseq [f (.listFiles src)] (add! target base f))
      (doto target (.putNextEntry (ent base src)) (write! src) (.closeEntry)))))

(defn jar [handler]
  (fn [spec]
    (let [artifact-id (when-let [p (name (get-in spec [:pom :project]))]
                        (clojure.string/replace p #"^[^/]*/" ""))
          version     (get-in spec [:pom :version])
          jar-name    (str (if artifact-id (str artifact-id "-" version) "out") ".jar") 
          jspec       (merge-with
                        (comp (partial some identity) vector)
                        (merge dfl-opts (:jar spec))
                        {:output-dir (mkdir ::jar)})
          jar-file    (file (:output-dir jspec) jar-name)
          directories (->> (get-in spec [:boot :directories])
                        (union (:resources jspec))
                        (map file))
          manifest    (make-manifest (:main jspec) (:manifest jspec))]
      (with-open [j (JarOutputStream. (output-stream jar-file) manifest)]
        (doseq [d directories] (add! j d d)))
      (when-let [pom (get-in spec [:pom :xml])]
        (spit (file (:output-dir jspec) "pom.xml") pom))
      (handler (assoc spec :jar jspec)))))
