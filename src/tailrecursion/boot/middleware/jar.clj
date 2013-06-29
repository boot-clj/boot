(ns tailrecursion.boot.middleware.jar
  (:import
    [java.io File]
    [java.util.jar JarOutputStream JarEntry Manifest Attributes Attributes$Name])
  (:require
    [clojure.string                 :refer [split join]]
    [clojure.java.io                :refer [input-stream output-stream file]]
    [tailrecursion.boot.tmpregistry :refer [mk mkdir exists? unmk]]))

(def dfl-opts {:manifest nil :directories []})

(defn- relative-to [base f]
  (.getPath (.relativize (.toURI base) (.toURI f))))

(defn- make-manifest []
  (let [m (Manifest.)
        a (.getMainAttributes m)]
    (.put a Attributes$Name/MANIFEST_VERSION "1.0")
    m))

(defn- make-entry [^File base ^File src]
  (let [ok? #(or (not (.isDirectory src)) (.endsWith % "/"))
        ent (let [p (relative-to base src)] (str p (if (ok? p) "" "/")))]
    (doto (JarEntry. ent) (.setTime (.lastModified src)))))

(defn- write! [^JarOutputStream target ^File src]
  (let [buf (byte-array 1024)] 
    (with-open [in (input-stream src)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write target buf 0 n)
          (recur (.read in buf)))))))

(defn- add! [^JarOutputStream target ^File base ^File src]
  (printf "Adding '%s' to jar.\n" (.getPath src))
  (if (.isDirectory src)
    (doseq [f (.listFiles src)] (add! target base f))
    (doto target (.putNextEntry (make-entry base src)) (write! src) (.closeEntry))))

(defn jar [handler]
  (fn [spec]
    (let [artifact-id (when-let [p (name (get-in spec [:pom :project]))]
                        (clojure.string/replace p #"^[^/]*/" ""))
          version     (get-in spec [:pom :version])
          jar-name    (str (if artifact-id (str artifact-id "-" version) "out") ".jar") 
          {:keys [output-to manifest] :as jspec}
          (merge-with
            (comp (partial some identity) vector)
            (-> dfl-opts (merge (:jar spec)) (update-in [:output-to] file)) 
            {:output-dir (mkdir ::jar)})
          jar-file    (file (:output-dir jspec) jar-name)
          directories (map file (:directories jspec))] 
      (with-open [j (JarOutputStream. (output-stream jar-file) (make-manifest))]
        (doseq [d directories] (add! j d d)))
      (handler (assoc spec :jar jspec)))))
