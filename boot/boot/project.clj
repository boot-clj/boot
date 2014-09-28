(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot version
  :description "Placeholder to synchronize other boot component versions."
  :url "https://github.com/tailrecursion/boot"
  :scm {:url "git@github.com:tailrecursion/boot.git"})

