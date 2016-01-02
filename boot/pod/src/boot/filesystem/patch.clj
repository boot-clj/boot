(ns boot.filesystem.patch)

(defmulti  patch (fn [before after link] (type (or before after))))
(defmethod patch :default [_ _ _] nil)
