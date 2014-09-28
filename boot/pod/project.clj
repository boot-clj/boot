(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/pod version
  :aot :all
  :dependencies [[boot/base                               ~version :scope "provided"]
                 [org.clojure/clojure                     "1.6.0"  :scope "provided"]
                 [org.tcrawley/dynapath                   "0.2.3"  :scope "compile"]
                 [org.projectodd.shimdandy/shimdandy-impl "1.0.1"  :scope "compile"]])
