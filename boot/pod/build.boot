(import boot.App)

(def +version+ (App/config "BOOT_VERSION"))

(set-env!
 :source-paths #{"src" "test"}
 :dependencies [['org.clojure/clojure "1.7.0"] ;; has to run 1.7.0 or boot-alt-test will fail
                ['boot/aether +version+]
                ['org.tcrawley/dynapath "1.0.0"]
                ['org.projectodd.shimdandy/shimdandy-impl "1.2.0"]

                ['metosin/boot-alt-test "0.4.0-20171122.125813-6" :scope "test"]])

(ns-unmap 'boot.user 'test)

(require '[metosin.boot-alt-test :refer [alt-test]])

(deftask test []
  (comp
   (with-pass-thru [fs]
     (boot.util/info "Testing against version %s\n" (App/config "BOOT_VERSION")))
   (alt-test)))
