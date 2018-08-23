(ns boot.cli-test
  (:require [boot.cli :as cli]
            [boot.from.clojure.tools.cli :as tools-cli]
            [clojure.test :as t]))

;; this function is mocked because the original uses templates, aka runs in
;; macro context.
(defn- argspec->cli-argspec
  ([short long type doc]
   (argspec->cli-argspec short long nil type doc))
  ([short long optarg type doc]
   (let [doc (if-not (empty? doc) doc (format "The %s option." long))]
     ((fnil into [])
      (when short [:short-opt (str "-" short)])
      [:id           (keyword long)
       :long-opt     (str "--" long)
       :required     (when optarg (str optarg))
       :desc         (#'boot.cli/format-doc short optarg type doc)
       :parse-fn     (#'boot.cli/parse-fn optarg)
       :assoc-fn     (#'boot.cli/assoc-fn optarg type)]))))

(defn- calculate-cli-args-&-opts
  "Emulate the arg transformation code in the boot.cli/clifn macro"
  [args argspecs]
  (let [cli-args (->> (#'boot.cli/argspec-seq argspecs)
                      (mapv (partial apply argspec->cli-argspec)))
        split (#'boot.cli/split-args args)
        [opts _] (#'boot.cli/separate-cli-opts (:cli split) cli-args)]
    [cli-args opts]))

(t/deftest cli-tests
  (t/testing "parse-opts with simple optargs"
    (let [argspecs '[f foo bool "Enable foo behavior."]
          args ["-f"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "simple flag optarg should not report errors")
      (t/is (= {:foo true} (:options parsed)) "simple flag optarg should work"))

    (let [argspecs '[f foo LEVEL int "The initial foo level."]
          args ["-f" "100"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "simple counter optarg should not report errors")
      (t/is (= {:foo 100} (:options parsed)) "simple counter optarg should work"))

    (let [argspecs '[f foo LEVELS #{int} "The set of initial foo levels."]
          args ["-f" "100" "-f" "200" "-f" "300"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "multi-option set optarg should not report errors")
      (t/is (= {:foo #{100 200 300}} (:options parsed)) "multi-option optarg should work"))

    (let [argspecs '[f foo LEVELS [int] "The set of initial foo levels."]
          args ["-f" "100" "-f" "200" "-f" "300"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "multi-option vector optarg should not report errors")
      (t/is (= {:foo [100 200 300]} (:options parsed)) "multi-option vector optarg should work")))

  (t/testing "parse-opts with complex optargs"
    (let [argspecs '[f foo FOO=BAR {kw sym} "The foo option."]
          args ["-f" "foo=bar"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "map split optarg should not report errors")
      (t/is (= {:foo {:foo 'bar}} (:options parsed)) "map split optarg should work"))

    (let [argspecs '[a parent SYM:VER=PATH [sym str str]]
          args ["-a" "prj:1.3=parent"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "[fix #578] optarg with sequential types should not report errors")
      (t/is (= {:parent ['prj "1.3" "parent"]} (:options parsed)) "[fix #578]  optarg with sequential should work"))

    (let [argspecs '[a parent SYM:VER=PATH [sym str str]]
          args ["-a" "overridden:X.X=uninteresting" "-a" "prj:1.3=parent"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "[fix #578] optarg with sequential types should not report errors with multiple arguments")
      (t/is (= {:parent ['prj "1.3" "parent"]} (:options parsed)) "[fix #578]  optarg with sequential should only include the last of multiple arguments"))

    (let [argspecs '[a parent SYM:VER=PATH=MORE [sym str str kw]]
          args ["-a" "prj:1.3=parent=more-stuff"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "[fix #578] even more targets should not report errors")
      (t/is (= {:parent ['prj "1.3" "parent" :more-stuff]} (:options parsed)) "[fix #578] even more targets optarg should work"))

    (let [argspecs '[a parent SYM:VER=PATH #{[sym str str]}]
          args ["-a" "prj:1.3=parent"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "set of vectors optarg should not report errors with a single argument")
      (t/is (= {:parent #{['prj "1.3" "parent"]}} (:options parsed)) "set of vectors optarg should work with single arguments"))

    (let [argspecs '[a parent SYM:VER=PATH #{[sym str str]}]
          args ["-a" "foo:3.3=bar" "-a" "baz:1.4=quux"]
          [cli-args opts] (calculate-cli-args-&-opts args argspecs)
          parsed (tools-cli/parse-opts opts cli-args)]
      (t/is (empty? (:errors parsed)) "set of vectors optarg should not report errors with multiple arguments")
      (t/is (= {:parent #{['baz "1.4" "quux"] ['foo "3.3" "bar"]}} (:options parsed)) "set of vectors optarg should work with multiple arguments"))

    (t/testing "[#707] Single vector optarg" ;; thanks Sean
      (let [argspecs '[a args ARG [str] "the arguments for the application."]
            args ["-a" "foo" "-a" "bar"]
            [cli-args opts] (calculate-cli-args-&-opts args argspecs)
            parsed (tools-cli/parse-opts opts cli-args)]
        (t/is (empty? (:errors parsed)) "should not report errors with multiple arguments")
        (t/is (= {:args ["foo" "bar"]} (:options parsed)) "should work keep order")))

    (t/testing "[#707] Single vector optarg" ;; thanks Alexander
      (let [argspecs '[f function SYMBOL [sym] "Functions to execute"
                       e eval     FORM   [edn] "Forms to execute"
                       p print           bool  "Print results to *out*"
                       P post            bool  "Execute after rather than before subsequent tasks"
                       o once            bool  "Run only once if used with watch"]
            args ["-e" "(+ 2 2)"]
            [cli-args opts] (calculate-cli-args-&-opts args argspecs)
            parsed (tools-cli/parse-opts opts cli-args)]
        (t/is (empty? (:errors parsed)) "a symbol optarg should not report errors")
        (t/is (= {:eval ['(+ 2 2)]} (:options parsed)) "a symbol optarg should convert to code")))))
