(ns boot.repl-client
  (:require
   [reply.main :as reply]))

(def default-opts {:color true :history-file ".nrepl-history"})

(defn cprint [x]
  (require 'puget.printer)
  ((resolve 'puget.printer/cprint) (read-string x)))

(defn client [opts]
  (let [p (or (:port opts) (try (slurp ".nrepl-port") (catch Throwable _)))
        h (or (:host opts) "127.0.0.1")
        o (let [o (assoc (merge default-opts opts) :attach (str h ":" p))]
            (if-not (:color o) o (-> o (dissoc :color) (assoc :print-value cprint))))]
    (assert (and h p) "host and/or port not specified for REPL client")
    (future (require 'puget.printer)) ;; takes ~3s, preload while reply launches
    (reply/launch-nrepl o)))
