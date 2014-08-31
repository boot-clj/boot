(ns boot.from.digest
  {:boot/from :tebeka/clj-digest
   :author "Miki Tebeka <miki.tebeka@gmail.com>"
   :doc "Message digest algorithms for Clojure"}
  (:use [clojure.string :only (split lower-case)])
  (:import java.util.Arrays
           (java.security MessageDigest Security Provider)
           (java.io FileInputStream File InputStream)))

; Default buffer size for reading
(def ^:dynamic *buffer-size* 1024)

(defn- read-some
  "Read some data from reader. Return [data size] if there's more to read,
  otherwise nil."
  [^InputStream reader]
  (let [^bytes  buffer (make-array Byte/TYPE *buffer-size*)
        size (.read reader buffer)]
    (when (> size 0)
      (if (= size *buffer-size*) buffer (Arrays/copyOf buffer size)))))

(defn- byte-seq
  "Return a sequence of [data size] from reader."
  [^InputStream reader]
  (take-while (complement nil?) (repeatedly (partial read-some reader))))

(defn- signature
  "Get signature (string) of digest."
  [^MessageDigest algorithm]
  (let [size (* 2 (.getDigestLength algorithm))
        sig (.toString (BigInteger. 1 (.digest algorithm)) 16)
        padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))

(defprotocol Digestible
  (-digest [message algorithm]))

(extend-protocol Digestible
  (class (make-array Byte/TYPE 0))
  (-digest  [message algorithm]
    (-digest [message] algorithm))
  
  java.util.Collection
  ;; Code "borrowed" from 
  ;; * http://www.holygoat.co.uk/blog/entry/2009-03-26-1
  ;; * http://www.rgagnon.com/javadetails/java-0416.html 
  (-digest [message algorithm]
    (let [^MessageDigest algo (MessageDigest/getInstance algorithm)]
      (.reset algo)
      (doseq [^bytes b message] (.update algo b))
      (signature algo)))

  String
  (-digest [message algorithm]
    (-digest [(.getBytes message)] algorithm))
  
  InputStream
  (-digest [reader algorithm]
    (-digest (byte-seq reader) algorithm))
  
  File
  (-digest [file algorithm]
    (with-open [f (FileInputStream. file)]
      (-digest f algorithm)))

  nil
  (-digest [message algorithm]
    nil))

(defn digest
  "Returns digest for message with given algorithm."
  [algorithm message]
  (-digest message algorithm))

(defn algorithms []
  "List support digest algorithms."
  (let [providers (into [] (Security/getProviders))
        names (mapcat (fn [^Provider p] (enumeration-seq (.keys p))) providers)
        digest-names (filter #(re-find #"MessageDigest\.[A-Z0-9-]+$" %) names)]
    (set (map #(last (split % #"\.")) digest-names))))

(defn- create-fns []
  "Create utility function for each digest algorithms.
   For example will create an md5 function for MD5 algorithm."
  (dorun (map #(intern 'boot.from.digest (symbol (lower-case %)) (partial digest %))
              (algorithms))))

; Create utililty functions such as md5, sha-2 ...
(create-fns)
