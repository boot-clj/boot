(defproject boot/worker "2.0.0-SNAPSHOT"
  :aot :all
  :dependencies [[org.clojure/clojure                   "1.6.0"          :scope "provided"]
                 [boot/base                             "2.0.0-SNAPSHOT" :scope "provided"]
                 [boot/aether                           "2.0.0-SNAPSHOT" :scope "compile"]
                 [reply                                 "0.3.4"          :scope "compile"]
                 [alandipert/desiderata                 "1.0.2"          :scope "compile"]
                 [org.clojure/data.xml                  "0.0.7"          :scope "compile"]
                 [javazoom/jlayer                       "1.0.1"          :scope "compile"]
                 [tailrecursion/clojure-adapter-servlet "0.1.0-SNAPSHOT" :scope "compile"]])
