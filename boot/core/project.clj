(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/core version
  :aot          [#"^(?!boot\.repl-server).*$"]
  :jar-exclusions [#"^clojure/"]
  :description  "Core boot module–boot scripts run in this pod."
  :url          "http://github.com/boot-clj/boot"
  :scm          {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories [["clojars"  {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license      {:name "Eclipse Public License"
                 :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"  :scope "provided"]
                 [boot/base           ~version :scope "provided"]
                 [boot/pod            ~version :scope "compile"]])

