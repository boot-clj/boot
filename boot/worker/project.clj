(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/worker version
  :aot :all
  :java-source-paths ["third_party/barbarywatchservice/src"]
  :dependencies [[org.clojure/clojure                   "1.6.0"  :scope "provided"]
                 [boot/base                             ~version :scope "provided"]
                 [boot/aether                           ~version :scope "compile"]
                 [s3-wagon-private                      "1.1.2"  :scope "compile"]
                 [net.java.dev.jna/jna                  "4.1.0"  :scope "compile"]
                 [reply                                 "0.3.4"  :scope "compile"]
                 [alandipert/desiderata                 "1.0.2"  :scope "compile"]
                 [org.clojure/data.xml                  "0.0.7"  :scope "compile"]
                 [javazoom/jlayer                       "1.0.1"  :scope "compile"]])
