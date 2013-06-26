(ns tailrecursion.boot.middleware.time
  (:refer-clojure :exclude [time]))

(defn time [handler & [msg]]
  (fn [spec]
    (when msg (println msg))
    (clojure.core/time (handler spec))))

