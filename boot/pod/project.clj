(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/pod version
  :aot            [#"^(?!boot\.repl-server).*$"]
  :jar-exclusions [#"^clojure/core/"]
  :description    "Boot pod module–this is included with all pods."
  :url            "https://github.com/boot-clj/boot"
  :scm            {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories   [["clojars"        {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]
                   ["sonatype"       {:url "https://oss.sonatype.org/content/repositories/releases"}]
                   ["sonatype-snaps" {:url "https://oss.sonatype.org/content/repositories/snapshots"}]]
  :license        {:name "Eclipse Public License"
                   :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies   [[boot/base                               ~version :scope "provided"]
                   [boot/bootstrap                          "3.0.0-SNAPSHOT" :scope "compile"]
                   [org.clojure/clojure                     "1.10.0"  :scope "provided"]
                   [org.tcrawley/dynapath                   "1.0.0"  :scope "compile"]
                   [org.projectodd.shimdandy/shimdandy-impl "1.2.1"  :scope "compile"]])
