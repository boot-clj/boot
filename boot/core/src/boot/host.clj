(ns boot.host
  (:require [clojure.string :as str]
            [bootstrap.config :as conf]))

(defn isWindows []
  (str/starts-with? (:os-name (conf/config)) "Windows"))
