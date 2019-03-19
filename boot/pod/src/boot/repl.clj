(ns boot.repl
  (:require
    [clojure.java.io :as io]
    [boot.pod        :as pod]
    [boot.file       :as file]
    [boot.util       :as util]
    [boot.from.io.aviso.repl :as pretty-repl]
    [boot.from.io.aviso.exception :refer [*fonts*]]))

(def ^:dynamic *default-dependencies*
  (atom '[[nrepl/nrepl "0.6.0"]]))

(defn ^:private disable-exception-colors
  [handler]
  (fn [msg]
    (binding [*fonts* nil]
      (handler msg))))

(def ^:dynamic *default-middleware*
  (atom (if-not @util/*colorize?* [disable-exception-colors] [])))

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
    (pretty-repl/install-pretty-exceptions)
    (when-let [deps (nrepl-dependencies opts)]
      (pod/add-dependencies (assoc pod/env :dependencies deps)))))

(defn launch-nrepl
  "See #boot.task.built-in/repl for explanation of options."
  [{:keys [bind port init-ns middleware handler] :as options}]
  (let [opts (->> options setup-nrepl-env!)]
    (@start-server opts)))

(defn launch-bare-repl
  [{to-eval :eval,
    :keys [init init-ns]
    :or {init-ns 'boot.user}}]
  (require 'clojure.main 'clojure.repl)
  ((resolve 'clojure.main/repl)
   :init (fn []
           (when init
             (load-file init))
           (when to-eval
             (eval to-eval))
           (in-ns init-ns)
           (when (= 'boot.user init-ns)
             (refer 'clojure.repl)))))

(defn launch-socket-server
  "See #boot.task.built-in/socket-server for explanation of options."
  [{:keys [bind port accept]}]
  (let [opts {:host (or bind "127.0.0.1")
              :port (or port 0)
              :name (gensym "socket-server")
              :accept (or accept 'clojure.core.server/repl)}]
    (try
      (require 'clojure.core.server)
      (catch java.io.FileNotFoundException e
        (throw (ex-info "Socket server requires Clojure version 1.8.0 or above"
                        {:version (clojure-version)}
                        e))))
    (let [effective-port (-> ((resolve 'clojure.core.server/start-server) opts)
                             (.getLocalPort))]
      (doto (io/file ".socket-port") .deleteOnExit (spit effective-port))
      (util/info "Socket server started on port %s on host %s.\n" effective-port (:host opts)))))
