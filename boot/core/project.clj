(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/core version
  :aot            :all
  :jar-exclusions [#"^clojure/core/"]
  :description    "Core boot module–boot scripts run in this pod."
  :url            "https://github.com/boot-clj/boot"
  :scm            {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories   [["clojars"  {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license        {:name "Eclipse Public License"
                   :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies   [[org.clojure/clojure "1.9.0"  :scope "provided"]
                   [boot/base           ~version :scope "provided"]
                   [boot/pod            ~version :scope "compile"]
                   [boot/boostrap       "3.0.0-SNAPSHOT" :scope "provided"]])
