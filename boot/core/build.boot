(set-env!
 :source-paths #{"test"}
 :dependencies '[[org.clojure/tools.reader "1.0.0-alpha2"]])

(require '[boot.test :refer [runtests test-report test-exit]]
         'boot.task.built-in-test
         'boot.test-test)

(import boot.App)

(ns-unmap 'boot.user 'test)

(deftask test []
  (boot.util/info "Testing against version %s\n" (App/config "BOOT_VERSION"))
  (comp (runtests)
        (test-report)
        (test-exit)))
