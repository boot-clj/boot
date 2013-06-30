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

(defn- artifact-info [pom]
  (when (and (:project pom) (:version pom))
    (let [p (cycle (split (str (:project pom)) #"/"))]
      {:group-id    (first p)
       :artifact-id (second p)
       :version     (:version pom)
       :pom-xml     (:xml pom)})))

(defn jar [handler]
  (fn [spec]
    (let [{:keys [artifact-id group-id version pom-xml]} (artifact-info (:pom spec))
          jar-name    (str (if artifact-id (str artifact-id "-" version) "out") ".jar") 
          jspec       (merge-with
                        (comp (partial some identity) vector)
                        (merge dfl-opts (:jar spec))
                        {:output-dir (mkdir ::jar)})
          jar-file    (file (:output-dir jspec) jar-name)
          directories (->> (get-in spec [:boot :directories])
                        (union (:resources jspec))
                        (map file))
          pom-dir     (mkdir ::pom)
          manifest    (make-manifest (:main jspec) (:manifest jspec))]
      (with-open [j (JarOutputStream. (output-stream jar-file) manifest)]
        (when pom-xml
          (let [pom-path ["META-INF" "maven" group-id artifact-id "pom.xml"]
                pom-file (doto (apply file pom-dir pom-path) make-parents)]
            (spit pom-file pom-xml)
            (add! j pom-dir pom-dir)))
        (doseq [d directories] (add! j d d)))
      (handler (assoc spec :jar jspec)))))
