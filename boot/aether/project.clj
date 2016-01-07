(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/aether version
  :aot :all
  :jar-exclusions [#"^clojure/core/"]
  :description  "Boot aether module–performs maven dependency resolution."
  :url          "http://github.com/boot-clj/boot"
  :scm          {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories [["clojars" {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license      {:name "Eclipse Public License"
                 :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure      "1.6.0"  :scope "compile"]
                 [boot/base                ~version :scope "provided"]
                 [boot/pod                 ~version :scope "compile"]
                 [com.cemerick/pomegranate "0.3.0"  :scope "compile"]
                 ;; updated wagon-http to lein's version, so the code could port
                 [org.apache.maven.wagon/wagon-http "2.9"]])
