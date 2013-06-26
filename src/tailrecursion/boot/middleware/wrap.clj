(ns tailrecursion.boot.middleware.wrap)

(defn wrap-> [handler f & args]
  (fn [spec]
    (apply f (handler spec) args)))

(defn wrap->> [handler f & args]
  (fn [spec]
    (apply f (concat args [(handler spec)]))))
