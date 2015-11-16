(ns boot.repl
  (:require
    [clojure.java.io :as io]
    [boot.pod        :as pod]
    [boot.file       :as file]
    [boot.util       :as util]))

(def ^:dynamic *default-dependencies*
  (atom '[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]]]))

(def ^:dynamic *default-middleware*
  (atom (if-not @util/*colorize?* [] ['boot.from.io.aviso.nrepl/pretty-middleware])))

(def ^:private start-server
  (delay (require 'boot.repl-server)
         @(resolve 'boot.repl-server/start-server)))

(defn- nrepl-dependencies
  [{:keys [default-dependencies]}]
  (seq (->> (or default-dependencies @*default-dependencies*)
            (remove pod/dependency-loaded?))))

(defn- nrepl-middleware
  [{:keys [default-middleware] :as opts}]
  (assoc opts :default-middleware (or default-middleware @*default-middleware*)))

(defn- rm-rf!
  [^java.io.File dir]
  (->> dir io/file file/file-seq-nofollow reverse (map (memfn delete)) doall))

(defn- delete-on-exit!
  [^java.io.File dir]
  (util/with-let [dir dir]
    (->> #(rm-rf! dir) Thread. (.addShutdownHook (Runtime/getRuntime)))))

(defn- compile-path
  [opts]
  (->> "boot-repl" file/tmpdir delete-on-exit! (assoc opts :compile-path)))

(defn- setup-nrepl-env!
  [opts]
  (util/with-let [{:keys [compile-path]} (-> opts compile-path nrepl-middleware)]
    (pod/add-classpath compile-path)
    (when-let [deps (nrepl-dependencies opts)]
      (pod/add-dependencies (assoc pod/env :dependencies deps)))))

(defn launch-nrepl
  "See #boot.task.built-in/repl for explanation of options."
  [{:keys [bind port init-ns middleware handler] :as options}]
  (let [opts (->> options setup-nrepl-env!)]
    (@start-server opts)))
