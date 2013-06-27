(defproject tailrecursion/boot "0.1.0-SNAPSHOT"
  :description "Dependency setup/build tool for Clojure"
  :url "https://github.com/tailrecursion/boot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.cemerick/pomegranate "0.2.0" :exclusions [org.clojure/clojure]]
                 ; for pom middleware
                 [org.apache.maven/maven-model "3.0.4"
                  :exclusions
                  [org.codehaus.plexus/plexus-utils]]
                 ; for cljsbuild middleware 
                 [org.clojure/clojurescript "0.0-1820"]
                 ; for mtime middleware
                 [digest "1.4.3"]]
  :main tailrecursion.boot)
