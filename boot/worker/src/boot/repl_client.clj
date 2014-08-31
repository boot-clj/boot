(ns boot.repl-client
  (:require
   [reply.main :as reply]))

(def default-opts {:color true :history-file ".nrepl-history"})

(defn client [opts]
  (let [p (or (:port opts) (try (slurp ".nrepl-port") (catch Throwable _)))
        h (or (:host opts) "127.0.0.1")]
    (assert (and h p) "host and/or port not specified for REPL client")
    (-> default-opts (assoc :attach (str h ":" p)) (merge opts)
      (select-keys [:attach :history-file :color]) reply/launch-nrepl)))
