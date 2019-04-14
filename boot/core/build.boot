(set-env!
 :source-paths #{"src" "test"}
 :dependencies '[[org.clojure/tools.reader "1.0.0-alpha2"]])
                 ;[metosin/boot-alt-test "0.3.2" :scope "test"]])

;(ns-unmap 'boot.user 'test)

;(require '[boot.test :refer [runtests test-report test-exit]]
;         '[metosin.boot-alt-test :refer [alt-test]]
;         'boot.test-test)

;(import boot.App)

;(deftask integration-test []
;  (comp
;   (runtests)
;   (test-report)
;   (test-exit))

;(deftask unit-test []
;  (alt-test :test-matcher #"boot\.cli-test"))

;(deftask test []
;  (comp
;   (with-pass-thru [fs]
;     (boot.util/info "Testing against version %s\n" (App/config "BOOT_VERSION"))
;   (unit-test)
;   (integration-test))
