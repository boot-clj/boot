(ns boot.host
  (:require [clojure.string :as str]
            [bootstrap.config :as conf]))

(defn windows? []
  (-> (:os-name (conf/config))
    (str/lower-case)
    (str/starts-with? "win")))
