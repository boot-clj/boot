(ns boot.repl-server
  (:require
   [boot.core                              :as core]
   [boot.from.io.aviso.nrepl               :as pretty]
   [clojure.java.io                        :as io]
   [clojure.tools.nrepl.server             :as server]
   [clojure.tools.nrepl.middleware         :as middleware]
   [clojure.tools.nrepl.middleware.session :as session])
  (:import
   [java.util.concurrent ConcurrentLinkedQueue]))

(def ^:private default-opts
  {:bind       nil
   :port       nil
   :handler    nil
   :middleware []})

(defn- ^{:boot/from :technomancy/leiningen} wrap-init-ns
  [init-ns]
  (with-local-vars
      [wrap-init-ns'
       (fn [h]
         ;; this needs to be a var, since it's in the nREPL session
         (with-local-vars [init-ns-sentinel nil]
           (fn [{:keys [session] :as msg}]
             (when-not (@session init-ns-sentinel)
               (swap! session assoc
                 init-ns-sentinel true
                 (var *ns*)       (try (require init-ns) (create-ns init-ns)
                                       (catch Throwable t (create-ns 'user)))))
             (h msg))))]
    (doto wrap-init-ns'
      ;; set-descriptor! currently nREPL only accepts a var
      (middleware/set-descriptor!
        {:requires #{#'session/session}
         :expects #{"eval"}})
      (alter-var-root (constantly @wrap-init-ns')))))

(middleware/set-descriptor!
  #'pretty/pretty-middleware
  {:requires #{} :expects #{}})

(def ^:private flash           (ConcurrentLinkedQueue.))
(def ^:private session-flashes (atom {}))

(defn send-flash [msg] (.add flash msg))

(defn wrap-flash
  [h]
  (fn [{:keys [session] :as msg}]
    (let [ret (h msg)]
      (when-not (@session-flashes session)
        (swap! session-flashes assoc session (ConcurrentLinkedQueue.)))
      (loop [msg (.poll flash)]
        (when msg
          (doseq [q (vals @session-flashes)]
            (.add q msg))
          (recur (.poll flash))))
      (when-let [session-err
                 (some-> @@#'session/sessions (get session) deref (get #'*err*))]
        (binding [*out* session-err]
          (when-let [flash (get @session-flashes session)]
            (loop [msg (.poll flash)]
              (when msg
                (println msg) (flush)
                (recur (.poll flash)))))))
      ret)))

(middleware/set-descriptor! #'wrap-flash
  {:requires #{} :expects #{} :handles {}})

(def ^:dynamic *default-middleware* [#'wrap-flash #'pretty/pretty-middleware])

(defn ->var
  [thing]
  (if-not (symbol? thing)
    thing
    (do (assert (and (symbol? thing) (namespace thing))
          (format "expected namespaced symbol (%s)" thing))
        (require (symbol (namespace thing)))
        (resolve thing))))

(defn ->mw-list
  [thing]
  (when thing
    (let [thing* (->var thing)
          thing  (if (var? thing*) @thing* thing*)]
      (if-not (sequential? thing)
        [thing*]
        (mapcat ->mw-list thing)))))

(defn start-server
  [opts]
  (let [{:keys [bind handler middleware init-ns]
         :as opts}     (merge default-opts opts)
        init-ns        (or init-ns 'boot.user)
        init-ns-mw     [(wrap-init-ns init-ns)]
        user-mw        (->mw-list middleware)
        middleware     (->> (concat init-ns-mw *default-middleware* user-mw)
                         (map ->var))
        handler        (-> (or (->var handler) server/default-handler)
                         (apply middleware))
        opts           (->> (-> (assoc opts :handler handler)
                              (select-keys [:bind :port :handler]))
                         (reduce-kv #(if-not %3 %1 (assoc %1 %2 %3)) {}))
        {:keys [port]} (apply server/start-server (mapcat identity opts))
        bind           (or (:bind opts) "0.0.0.0")]
    (doto (io/file ".nrepl-port") .deleteOnExit (spit port))
    (printf "nREPL server listening: %s:%s\n" bind port)))
