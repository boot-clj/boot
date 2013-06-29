(ns tailrecursion.boot.middleware.jar
  (:import
    [java.io File]
    [java.util.jar JarOutputStream JarEntry Manifest Attributes Attributes$Name])
  (:require
    [clojure.string                 :refer [split join]]
    [clojure.java.io                :refer [input-stream output-stream file]]
    [tailrecursion.boot.tmpregistry :refer [mk mkdir exists? unmk]]))

(def dfl-opts {:manifest nil :directories []})

(defn- make-manifest []
  (let [m (Manifest.)
        a (.getMainAttributes m)]
    (.put a Attributes$Name/MANIFEST_VERSION "1.0")
    m))

(defn- zip-name [^File x]
  (let [p (.getPath x)]
    (if (and (.isDirectory x) (not (.endsWith p "/"))) (str p "/") p)))

(defn- make-entry [^File src]
  (doto (JarEntry. (zip-name src)) (.setTime (.lastModified src))))

(defn- write! [^JarOutputStream target ^File src]
  (let [buf (byte-array 1024)
        in  (input-stream src)]
    (try
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write target buf 0 n)
          (recur (.read in buf))))
      (finally (.close in)))))

(defn- add! [^JarOutputStream target ^File src]
  (printf "Adding '%s' to jar.\n" (.getPath src))
  (let [target (doto target (.putNextEntry (make-entry src)))]
    (if (.isDirectory src)
      (doall (map (partial add! (doto target (.closeEntry))) (.listFiles src)))
      (do (write! target src) (.closeEntry target)))))

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
          jar-file    (file (:output-dir jspec) jar-name)] 
      (with-open [j (JarOutputStream. (output-stream jar-file) (make-manifest))]
        (doall (map (comp (partial add! j) file) (:directories jspec)))) 
      (handler (assoc spec :jar jspec)))))
