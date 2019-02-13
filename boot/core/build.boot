(set-env!
 :source-paths #{"src" "test"}
 :dependencies '[[org.clojure/tools.reader "1.3.2" :exclusions [org.clojure/clojure]]
                 [metosin/bat-test "0.4.2" :scope "test"]])

(ns-unmap 'boot.user 'test)

(require '[boot.test :refer [runtests test-report test-exit]]
         '[metosin.bat-test :refer [bat-test]]
         'boot.task.built-in-test
         'boot.test-test)

(import boot.App)

(deftask integration-test []
  (comp
   (runtests)
   (test-report)
   (test-exit)))

(deftask unit-test []
  (bat-test :test-matcher #"boot\.cli-test"))

(deftask test []
  (comp
   (with-pass-thru [fs]
     (boot.util/info "Testing against version %s\n" (App/config "BOOT_VERSION")))
   (unit-test)
   (integration-test)))
