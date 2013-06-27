(ns tailrecursion.boot.middleware.time
  (:refer-clojure :exclude [time]))

(defn return [handler & [retval]]
  (fn [spec]
    (handler spec)
    retval))

(defn time [handler & [msg]]
  (fn [spec]
    (when msg (println msg))
    (clojure.core/time (handler spec))))

