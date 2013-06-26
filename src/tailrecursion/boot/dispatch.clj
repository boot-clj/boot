(ns tailrecursion.boot.dispatch
  (:require [clojure.core :as core])
  (:refer-clojure :exclude [resolve deref]))

(defmulti dispatch-cli (fn [env args] (first args)))

(defmethod dispatch-cli :default [env [k & _]]
  (println (format "No middlewares registered for command-line dispatch on %s."
                   (pr-str k))))
