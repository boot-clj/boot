(ns boot.filesystem.patch)

(defmulti  patch (fn [before after link] (type (or before after))))
(defmethod patch :default [_ _ _] nil)

(defmulti  patch-result (fn [before after] (type (or before after))))
(defmethod patch-result :default [_ after] after)
