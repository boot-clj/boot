(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/aether version
  :aot :all
  :dependencies [[org.clojure/clojure      "1.6.0"  :scope "compile"]
                 [boot/pod                 ~version :scope "compile"]
                 [com.cemerick/pomegranate "0.3.0"  :scope "compile"]])
