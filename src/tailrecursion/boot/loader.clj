;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.boot.loader
  (:require
   [clojure.java.io                     :as io]
   [clojure.string                      :as string]
   [clojure.pprint                      :as pprint]
   [clojure.stacktrace                  :as trace]
   [tailrecursion.boot.strap            :as strap]
   [tailrecursion.boot.classlojure.core :as cl])
  (:import
   [java.net URLClassLoader URL URI]
   java.lang.management.ManagementFactory)
  (:gen-class))

;; utility ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro guard [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defmacro with-rethrow [expr msg]
  `(try ~expr (catch Throwable e# (throw (Exception. ~msg e#)))))

(defmacro with-err [& body]
  `(binding [*out* *err*] ~@body (System/exit 1)))

(defmacro with-terminate [& body]
  `(try
     ~@body
     (System/exit 0)
     (catch Throwable e#
       (with-err (trace/print-cause-trace e#)))))

(defn auto-flush
  [writer]
  (proxy [java.io.PrintWriter] [writer]
    (write [s] (.write writer s) (flush))))

(defn warn
  [& more]
  (binding [*out* *err*] (apply printf more) (flush)))

(defn get-project [sym]
  (when-let [pform (->> (.. Thread currentThread getContextClassLoader
                          (getResources "project.clj"))
                     enumeration-seq
                     (map (comp read-string slurp))
                     (filter (comp (partial = sym) second))
                     first)]
    (->> pform (drop 1) (partition 2) (map vec) (into {}))))

;; loader ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def min-core-version "2.0.0")
(def dfl-repos #{"http://clojars.org/repo/" "http://repo1.maven.org/maven2/"})

(def ^:private core-dep     (atom nil))
(def ^:private cl2          (atom nil))
(def ^:private dependencies (atom nil))

(defn get-classloader
  "Returns classloader with only boot-classloader.jar in its class path. This
  classloader is used to segregate the pomegranate dependencies to keep them
  from interfering with project dependencies."
  []
  (when (compare-and-set! cl2 nil true)
    (let [tmp (doto (io/file ".boot") .mkdirs)
          in  (io/resource "boot-classloader.jar")
          out (io/file tmp "boot-classloader.jar")]
      (with-open [in  (io/input-stream in)
                  out (io/output-stream out)]
        (io/copy in out))
      (reset! cl2 (cl/classlojure (str "file:" (.getPath out))))))
  @cl2)

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn add-urls!
  "Add URLs (directories or JAR files) to the classpath."
  [urls]
  (when (seq urls)
    (let [meth  (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL]))
                  (.setAccessible true))
          cldr  (ClassLoader/getSystemClassLoader)
          urls  (->> urls (map io/file) (filter #(.exists %)) (map #(.. % toURI toURL)))]
      (doseq [url urls] (.invoke meth cldr (object-array [url]))))))

(defn resolve-dependencies! [deps repos]
  (cl/eval-in (get-classloader)
    `(do (require 'tailrecursion.boot-classloader)
         (tailrecursion.boot-classloader/resolve-dependencies! '~deps '~repos))))

(defn glob-match? [pattern path]
  (cl/eval-in (get-classloader)
    `(do (require 'tailrecursion.boot-classloader)
         (tailrecursion.boot-classloader/glob-match? ~pattern ~path))))

(defn add-dependencies! [deps & [repos]]
  (let [loaded (->> @dependencies (map first) set)
        specs  (->> deps
                 (remove (comp (partial contains? loaded) first))
                 (mapv (partial exclude (vec loaded)))
                 (#(resolve-dependencies! % (or repos dfl-repos))))]
    (swap! dependencies into (mapv :dep specs))
    (add-urls! (map #(->> % :jar (str "file:///") URL.) specs))))

(defrecord CoreVersion [depspec])

(defn print-core-version
  ([this]
     (let [[[_ version & _]] (:depspec this)]
       (printf "#tailrecursion.boot.core/version %s" (pr-str version))))
  ([this w]
     (let [[[_ version & _]] (:depspec this)]
       (.write w "#tailrecursion.boot.core/version ")
       (.write w (pr-str version)))))

(defmethod print-method CoreVersion [this w]
  (print-core-version this w))

(. clojure.pprint/simple-dispatch addMethod CoreVersion print-core-version)
(. clojure.pprint/code-dispatch addMethod CoreVersion print-core-version)

(defn install-core [version]
  (when (pos? (compare min-core-version version))
    (with-err
      (printf "boot: can't use boot.core version %s: need at least %s\n" version min-core-version)))
  (locking core-dep
    (let [core (->CoreVersion [['tailrecursion/boot.core version]])]
      (if @core-dep core (doto core ((partial reset! core-dep)))))))

(defn usage []
  (print
    (str
      "Usage: boot :strap\n"
      "       boot [arg ...]\n"
      "       boot <scriptfile.boot> [arg ...]\n\n"
      "Create a minimal boot script: `boot :strap > build.boot`\n\n")))

(defn wrong-ext [arg0]
  (printf "boot: script file doesn't have .boot extension: %s\n\n" arg0))

(defn script-not-found [arg0 badext]
  (print
    (str
      (format "boot: script file not found: %s\n" arg0)
      (if-not badext
        (if-not (.exists (io/file "boot.edn"))
          "\n"
          "\n** Found old-style boot.edn file. Run `boot :fixup > build.boot` to upgrade. **\n\n")
        (format "Perhaps %s should have the .boot extension?\n\n" badext)))))

(defn no-core-dep [arg0]
  (printf "boot: no boot.core dependency specified in %s\n\n" arg0))

(defn -main [& [arg0 & args :as args*]]
  (binding [*out* (auto-flush *out*)
            *err* (auto-flush *err*)]
    (with-terminate
      (reset! dependencies (:dependencies (get-project 'tailrecursion/boot)))
      (case arg0
        ":strap" (strap/strap add-dependencies!)
        ":fixup" (strap/fixup add-dependencies!)
        (let [file?       #(and % (.isFile (io/file %)))
              dotboot?    #(and % (.endsWith (.getName (io/file %)) ".boot"))
              script?     #(and % (dotboot? %))
              badext      (and (file? arg0) (not (script? arg0)) arg0)
              [arg0 args] (if (script? arg0) [arg0 args] ["build.boot" args*])]
          (when-not (file? arg0) (with-err (script-not-found arg0 badext) (usage)))
          (let [script (->> arg0 slurp (format "(%s)") read-string)]
            (if-let [core-dep* (:depspec @core-dep)]
              (do (add-dependencies! core-dep*)
                  (require 'tailrecursion.boot)
                  (let [main (find-var (symbol "tailrecursion.boot" "-main"))
                        info (-> (get-project 'tailrecursion/boot)
                               (assoc :dependencies @dependencies))]
                    (apply main info arg0 script args)))
              (with-err (no-core-dep arg0) (usage)))))))))
