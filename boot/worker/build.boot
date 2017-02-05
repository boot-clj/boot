(set-env!
 :source-paths #{"src" "test"}
 :dependencies '[[net.cgrand/parsley          "0.9.3" :exclusions [org.clojure/clojure]]
                 [mvxcvi/puget                "1.0.1"]
                 [reply                       "0.3.7"]
                 [cheshire                    "5.3.1"]
                 [clj-jgit                    "0.8.0"]
                 [clj-yaml                    "0.4.0"]
                 [javazoom/jlayer             "1.0.1"]
                 [net.java.dev.jna/jna        "4.1.0"]
                 [alandipert/desiderata       "1.0.2"]
                 [org.clojure/data.xml        "0.0.8"]
                 [org.clojure/data.zip        "0.1.1"]
                 [org.clojure/tools.namespace "0.2.11"]

                 [metosin/boot-alt-test "0.3.2" :scope "test"]])

(ns-unmap 'boot.user 'test)

(require '[metosin.boot-alt-test :refer [alt-test]])

(import boot.App)

(deftask test []
  (comp
   (with-pass-thru [fs]
     (boot.util/info "Testing against version %s\n" (App/config "BOOT_VERSION")))
   (alt-test)))
