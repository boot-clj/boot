(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/tasks version
  :aot            [#"^(?!boot\.repl-server).*$"]
  :jar-exclusions [#"^clojure/core/"]
  :description    "Boot tasks module–this includes built-in tasks."
  :url            "https://github.com/boot-clj/boot"
  :scm            {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories   [["clojars"        {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license        {:name "Eclipse Public License"
                   :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies   [[boot/base                               ~version :scope "provided"]
                   [boot/core                               ~version :scope "provided"]
                   [boot/pod                                ~version :scope "provided"]
                   [org.clojure/clojure                     "1.9.0"  :scope "provided"]])
