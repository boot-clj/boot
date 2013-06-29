(ns tailrecursion.boot.middleware.util)

(defn return [handler & [retval]]
  (fn [spec]
    (handler spec)
    retval))

(defn before [handler other-mw & mw-args]
  (fn [spec]
    (handler ((apply other-mw identity mw-args) spec))))

(defn after [handler other-mw & mw-args]
  (fn [spec]
    ((apply other-mw identity mw-args) (handler spec))))
