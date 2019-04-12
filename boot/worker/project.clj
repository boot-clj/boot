(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/worker version
  :aot :all
  :jar-exclusions [#"^clojure/core/"]
  :description  "Boot worker module–this is the worker pod for built-in tasks."
  :url          "https://github.com/boot-clj/boot"
  :scm          {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories [["clojars"  {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license      {:name "Eclipse Public License"
                 :url "http://www.eclipse.org/legal/epl-v10.html"}
  :java-source-paths ["third_party/barbarywatchservice/src"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :plugins      [[lein-ancient "0.6.15"]]
  :dependencies [[org.clojure/clojure         "1.6.0"  :scope "provided"]
                 [boot/base                   ~version :scope "provided"]
                 [boot/aether                 ~version]
                 ;; Suppress warnings from SLF4J via pomegranate via aether
                 [org.slf4j/slf4j-nop         "1.7.26"]
                 ;; see https://github.com/boot-clj/boot/issues/82
                 [net.cgrand/parsley          "0.9.3" :exclusions [org.clojure/clojure]]
                 [mvxcvi/puget                "1.1.2"]
                 [reply                       "0.4.3"]
                 [cheshire                    "5.8.1"]
                 [clj-jgit                    "0.8.10"]
                 [clj-yaml                    "0.4.0"]
                 [javazoom/jlayer             "1.0.1"]
                 [net.java.dev.jna/jna        "5.2.0"]
                 [alandipert/desiderata       "1.0.2"]
                 [org.clojure/data.xml        "0.0.8"]
                 [org.clojure/data.zip        "0.1.3"]
                 [org.clojure/tools.namespace "0.2.11"]])
