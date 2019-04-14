(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/base version
  :aot               [#"^(?!boot\.repl-server).*$"]
  :jar-exclusions    [#"^clojure/core/"]
  :java-source-paths ["src/main/java"]
  :description       "Boot base module–this is the classloader shim."
  :url               "https://github.com/boot-clj/boot"
  :scm               {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories      [["clojars"        {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]
                      ["sonatype"       {:url "https://oss.sonatype.org/content/repositories/releases"}]
                      ["sonatype-snaps" {:url "https://oss.sonatype.org/content/repositories/snapshots"}]]
  :license           {:name "Eclipse Public License"
                      :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies      [[org.clojure/clojure "1.9.0" :scope "provided"]
                      [boot/bootstrap "3.0.0-SNAPSHOT" :scope "compile"]
                      [org.projectodd.shimdandy/shimdandy-api "1.2.1"  :scope "compile"]
                      [junit/junit "3.8.1" :scope "test"]])
