(defproject boot/pod "2.0.0-SNAPSHOT"
  :aot :all
  :dependencies [[boot/base                               "2.0.0-SNAPSHOT" :scope "provided"]
                 [org.clojure/clojure                     "1.6.0"          :scope "provided"]
                 [org.tcrawley/dynapath                   "0.2.3"          :scope "compile"]
                 [org.projectodd.shimdandy/shimdandy-impl "1.0.1"          :scope "compile"]])
