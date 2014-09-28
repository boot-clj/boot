(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/core version
  :aot [#"^(?!boot\.repl-server).*$"]
  :dependencies [[org.clojure/clojure "1.6.0"  :scope "provided"]
                 [boot/base           ~version :scope "provided"]
                 [boot/pod            ~version :scope "compile"]])

