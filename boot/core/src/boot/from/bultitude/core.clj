(ns boot.from.bultitude.core
  {:boot/from :Raynes/bultitude}
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynapath.util :as dp])
  (:import (java.util.jar JarFile JarEntry)
           (java.util.zip ZipException)
           (java.io File BufferedReader PushbackReader InputStreamReader)
           (clojure.lang DynamicClassLoader)))

(declare namespace-forms-in-dir
         file->namespace-forms)

(defn- clj? [^File f]
  (and (not (.isDirectory f))
       (.endsWith (.getName f) ".clj")))

(defn- clj-jar-entry? [^JarEntry f]
  (and (not (.isDirectory f))
       (.endsWith (.getName f) ".clj")))

(defn- jar? [^File f]
  (and (.isFile f) (.endsWith (.getName f) ".jar")))

(defn- read-ns-form
  "Given a reader on a Clojure source file, read until an ns form is found."
  ([rdr] (read-ns-form rdr true))
  ([rdr ignore-unreadable?]
     (let [form (try (read rdr false ::done)
                     (catch Exception e
                       (if ignore-unreadable?
                         ::done
                         (throw e))))]
       (if (and (list? form) (= 'ns (first form)))
         form
         (when-not (= ::done form)
           (recur rdr ignore-unreadable?))))))

(defn ns-form-for-file
  ([file] (ns-form-for-file file true))
  ([file ignore-unreadable?]
     (with-open [r (PushbackReader. (io/reader file))]
       (read-ns-form r ignore-unreadable?))))

(defn namespaces-in-dir
  "Return a seq of all namespaces found in Clojure source files in dir."
  ([dir] (namespaces-in-dir dir true))
  ([dir ignore-unreadable?]
     (map second (namespace-forms-in-dir dir ignore-unreadable?))))

(defn namespace-forms-in-dir
  "Return a seq of all namespace forms found in Clojure source files in dir."
  ([dir] (namespace-forms-in-dir dir true))
  ([dir ignore-unreadable?]
     (for [^File f (file-seq (io/file dir))
           :when (and (clj? f) (.canRead f))
           :let [ns-form (ns-form-for-file f ignore-unreadable?)]
           :when ns-form]
       ns-form)))

(defn- ns-form-in-jar-entry
  ([jarfile entry] (ns-form-in-jar-entry jarfile entry true))
  ([^JarFile jarfile ^JarEntry entry ignore-unreadable?]
     (with-open [rdr (-> jarfile
                         (.getInputStream entry)
                         InputStreamReader.
                         BufferedReader.
                         PushbackReader.)]
       (read-ns-form rdr ignore-unreadable?))))

(defn- namespace-forms-in-jar
  ([jar] (namespace-forms-in-jar jar true))
  ([^File jar ignore-unreadable?]
     (try
       (let [jarfile (JarFile. jar)]
         (for [entry (enumeration-seq (.entries jarfile))
               :when (clj-jar-entry? entry)
               :let [ns-form (ns-form-in-jar-entry jarfile entry
                                                   ignore-unreadable?)]
               :when ns-form]
           ns-form))
       (catch ZipException e
         (throw (Exception. (str "jar file corrupt: " jar) e))))))

(defn- split-classpath [^String classpath]
  (.split classpath (System/getProperty "path.separator")))

(defn loader-classpath
  "Returns a sequence of File objects from a classloader."
  [loader]
  (map io/as-file (dp/classpath-urls loader)))

(defn classpath-files
  "Returns a sequence of File objects of the elements on the classpath."
  ([classloader]
     (map io/as-file (dp/all-classpath-urls classloader)))
  ([] (classpath-files (clojure.lang.RT/baseLoader))))

(defn- classpath->collection [classpath]
  (if (coll? classpath)
    classpath
    (split-classpath classpath)))

(defn- classpath->files [classpath]
  (map io/file classpath))

(defn file->namespaces
  "Map a classpath file to the namespaces it contains. `prefix` allows for
   reducing the namespace search space. For large directories on the classpath,
   passing a `prefix` can provide significant efficiency gains."
  [^String prefix ^File f]
  (map second (file->namespace-forms prefix f)))

(defn file->namespace-forms
  "Map a classpath file to the namespace forms it contains. `prefix` allows for
   reducing the namespace search space. For large directories on the classpath,
   passing a `prefix` can provide significant efficiency gains."
  ([prefix f] (file->namespace-forms prefix f true))
  ([^String prefix ^File f ignore-unreadable?]
     (cond
      (.isDirectory f) (namespace-forms-in-dir
                        (if prefix
                          (io/file f (-> prefix
                                         (.replaceAll "\\." "/")
                                         (.replaceAll "-" "_")))
                          f) ignore-unreadable?)
      (jar? f) (let [ns-list (namespace-forms-in-jar f ignore-unreadable?)]
                 (if prefix
                   (for [nspace ns-list
                         :let [sym (second nspace)]
                         :when (and sym (.startsWith (name sym) prefix))]
                     nspace)
                   ns-list)))))

(defn namespace-forms-on-classpath
  "Returs the namespaces forms matching the given prefix both on disk and
  inside jar files. If :prefix is passed, only return namespaces that begin with
  this prefix. If :classpath is passed, it should be a seq of File objects or a
  classpath string. If it is not passed, default to java.class.path and the
  current classloader, assuming it is a dynamic classloader."
  [& {:keys [prefix classpath ignore-unreadable?]
      :or {classpath (classpath-files) ignore-unreadable? true}}]
  (mapcat
   #(file->namespace-forms prefix % ignore-unreadable?)
   (->> classpath
        classpath->collection
        classpath->files)))

(defn namespaces-on-classpath
  "Return symbols of all namespaces matching the given prefix both on disk and
  inside jar files. If :prefix is passed, only return namespaces that begin with
  this prefix. If :classpath is passed, it should be a seq of File objects or a
  classpath string. If it is not passed, default to java.class.path and the
  current classloader, assuming it is a dynamic classloader."
  [& args]
  (map second (apply namespace-forms-on-classpath args)))

(defn path-for
  "Transform a namespace into a .clj file path relative to classpath root."
  [namespace]
  (str (-> (str namespace)
           (.replace \- \_)
           (.replace \. \/))
       ".clj"))

(defn doc-from-ns-form
  "Extract the docstring from a given ns form without evaluating the form. The docstring returned should be the return value of (:doc (meta namespace-symbol)) if the ns-form were to be evaluated."
  [ns-form]
  (:doc (meta (second (second (second (macroexpand ns-form)))))))
