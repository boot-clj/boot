(ns tailrecursion.boot.middleware.sync
  (:require
    [tailrecursion.boot.file :as f]))

(defn sync-dirs [algo handler dst & srcs]
  (fn [spec]
    (apply f/sync type dst srcs)
    (handler spec)))

(def sync-time (partial sync-dirs :time))
(def sync-hash (partial sync-dirs :hash))
