(ns boot.app
  (:require [clojure.java.io :as io]
            [bootstrap.config :as conf])
  (:import [java.net URLClassLoader]
           [java.util.concurrent Executors]
           [org.projectodd.shimdandy ClojureRuntimeShim])
  (:gen-class))

(defn- invoke-hooks [runtime hooks]
  (reduce #(when (.exists %2) (.invoke %1 "clojure.core/load-file" (.getPath %2)) %1) runtime hooks))

(def ^:private pods (atom []))

(defn- mkshim [name exec data jars]
  (let [urls     (map #(.toURL (.toURI %)) jars)
        classldr (URLClassLoader. urls (ClassLoader/getPlatformClassLoader))
        runtime  (ClojureRuntimeShim/newRuntime classldr)
        hooks    [(io/file (conf/boot-dir) "boot-shim.clj") (io/file "boot-shim.clj")]]
    (doto runtime
      (.setName (or name "anonymous"))
      (invoke-hooks hooks)
      (.require "boot.pod")
      (.invoke "boot.pod/seal-app-classloader")
      (.invoke "boot.pod/extend-addable-classloader")
      (.invoke "boot.pod/set-data!" data)
      (.invoke "boot.pod/set-pods!" pods)
      (.invoke "boot.pod/set-this-pod!" runtime)
      #(swap! pods conj %))))

(defn- set-pod-repo! [pod repo]
  (if-not repo pod
    (doto (.get pod)
      (.require "boot.aether")
      (.invoke "boot.aether/set-local-repo!" repo))))

(def ^:private pid (atom 0))

(defn Exit [code msg]
  (ex-info msg {:error-code code}))

(defn- run-boot [corepod workerpod args]
  (let [hooks (atom [])
        core  (.get @corepod)]
    (try
      (let [repo   (:boot-local-repo (conf/config) "default")
            worker (set-pod-repo! @workerpod repo)
            pid    (and (swap! pid inc) @pid)]
        (.require core "boot.main")
        (.invoke core "boot.main/-main" pid worker hooks args)
        -1)
      (catch Throwable t
        (println "Boot failed to start:")
        (if-let [exdata (ex-data t)]
           (:error-code exdata)
           (and (.printStackTrace t) -2)))
      (finally
        (doseq [hook hooks] @hook)
        (try (.close core)
          (catch InterruptedException ie -3))))))

(defn -main [& args]
  (let [exec      (Executors/newCachedThreadPool)
        shutdown  (future (.shutdown exec))
        corepod   (future (mkshim "core" exec nil corejars))
        workerpod (future (mkshim "worker" exec nil workerjars))]
    (.addShutdownHook (Runtime/getRuntime) (fn [] @shutdown))
    (System/exit (run-boot corepod workerpod args))))
