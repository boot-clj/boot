(ns tailrecursion.boot.middleware.sync
  (:require
    [clojure.java.io :refer [file]]
    [tailrecursion.boot.file :as f]))

(defn sync-dirs [algo handler dst & srcs]
  (let [dst (file dst), srcs (map file srcs)]
    (fn [spec]
      (apply f/sync algo dst srcs)
      (handler spec))))

(def sync-time (partial sync-dirs :time))
(def sync-hash (partial sync-dirs :hash))
