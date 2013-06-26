#boot/configuration
{:boot {:coordinates #{[reply "0.2.0"]}
        :directories #{"test"}}
 :pom {:project tailrecursion/boot
       :version "1.0.0-SNAPSHOT"
       :description "Boot rules"}}

(ns user
  (:require [tailrecursion.boot.middleware.pom :refer [wrap-pom]]))

(def pom
  (-> #(get-in % [:pom :pom.xml])
      (wrap-pom)))

(-> @boot/env pom print)
