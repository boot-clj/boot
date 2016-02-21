(set-env!
 :source-paths #{"test"}
 :dependencies '[[org.clojure/tools.reader "1.0.0-alpha2"]])

(require '[boot.task.built-in-test :refer :all])
(import boot.App)

(ns-unmap 'boot.user 'test)

;; or from cmd line
;; boot runtests -c "test1 --flag"
;;               -c "test2"
;;               -c ...

(deftask test []
  (boot.util/info "Testing against version %s\n" (boot.App/config "BOOT_VERSION"))
  (comp (runtests :commands #{"sift-with-meta-tests"
                              "sift-with-meta-invert-tests"
                              "sift-include-tests"
                              "sift-include-invert-tests"
                              "sift-to-asset-tests"
                              "sift-to-asset-invert-tests"
                              "sift-add-meta-tests"
                              "sift-add-meta-invert-tests"})
        (test-report)
        (test-exit)))
