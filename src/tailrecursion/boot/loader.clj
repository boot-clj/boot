;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.boot.loader
  (:require
   [clojure.java.io          :as io]
   [clojure.string           :as string]
   [clojure.pprint           :as pprint]
   [cemerick.pomegranate     :as pom]
   [clojure.stacktrace       :as trace]
   [tailrecursion.boot.strap :as strap])
  (:gen-class))

(def min-core-version "2.0.0")

(defmacro guard [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defmacro with-rethrow [expr msg]
  `(try ~expr (catch Throwable e# (throw (Exception. ~msg e#)))))

(defmacro with-err [& body]
  `(binding [*out* *err*] ~@body (System/exit 1)))

(defmacro with-terminate [expr]
  `(try
     ~expr
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

(defn info
  "Returns a map of version information for tailrecursion.boot.loader"
  []
  (let [[_ & kvs] (guard (read-string (slurp (io/resource "project.clj"))))]
    (->> kvs (partition 2) (map (partial apply vector)) (into {}))))

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(def exclude-clj (partial exclude ['org.clojure/clojure]))

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error}]
  (when (.endsWith name ".jar")
    (case type
      :started              (warn "Retrieving %s from %s\n" name repo)
      (:corrupted :failed)  (when err (warn "Error: %s\n" (.getMessage err)))
      nil)))

(defn ^:from-leiningen build-url
  "Creates java.net.URL from string"
  [url]
  (try (java.net.URL. url)
       (catch java.net.MalformedURLException _
         (java.net.URL. (str "http://" url)))))

(defn ^:from-leiningen get-non-proxy-hosts []
  (let [system-no-proxy (System/getenv "no_proxy")]
    (if (not-empty system-no-proxy)
      (->> (string/split system-no-proxy #",")
           (map #(str "*" %))
           (string/join "|")))))

(defn ^:from-leiningen get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
     (if-let [proxy (System/getenv key)]
       (let [url (build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host            (.getHost url)
          :port            (.getPort url)
          :username        username
          :password        password
          :non-proxy-hosts (get-non-proxy-hosts)}))))

(def core-dep    (atom nil))
(def dfl-repos   #{"http://clojars.org/repo/" "http://repo1.maven.org/maven2/"})

(defn add-dependencies!
  ([deps] (add-dependencies! deps dfl-repos))
  ([deps repos]
     (let [deps (mapv exclude-clj deps)]
       (pom/add-dependencies :coordinates        deps
         :repositories       (zipmap repos repos)
         :transfer-listener  transfer-listener
         :proxy              (get-proxy-settings)))))

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
    (let [core (->CoreVersion [(exclude-clj ['tailrecursion/boot.core version])])]
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
                        info (update-in (info) [:dependencies] into core-dep*)]
                    (apply main info arg0 script args)))
              (with-err (no-core-dep arg0) (usage)))))))))
