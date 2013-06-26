(ns tailrecursion.boot.dispatch
  (:require [clojure.core :as core])
  (:refer-clojure :exclude [resolve deref]))

(defn resolve [x]
  (or (and (symbol? x)
           (or (binding [*ns* 'user] (core/resolve x))
               (throw (RuntimeException. (str "unable to resolve symbol: " x)))))
      x))

(defn deref [x]
  (if (var? x) (core/deref x) x))

(def read-args (partial map (comp deref resolve read-string)))

(defmulti dispatch (fn [env args] (first args)))

(defmethod dispatch "pom" [{:keys [boot pom] :as env} [_ & args]]
  (println pom))

(defmethod dispatch "jar" [env [_ & args]]
  (println "JAR!"))

(defn main? [var]
  (and (or (= (:name (meta var)) '-main)
           (= (:name (meta var)) 'main))
       (not= (:ns (meta var)) (the-ns 'user))))

(defmethod dispatch :default [env [f & args]]
  (when f
    (if-let [v (resolve (read-string f))]
      (apply v (if (main? v) args (read-args args))))))

(defn try-dispatch [env args]
  (dispatch env args))
