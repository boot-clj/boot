(ns boot.test
  (:require
   [clojure.string :as string]
   [clojure.walk   :as walk]
   [clojure.test   :as test]
   [boot.core      :as core]
   [boot.pod       :as pod]
   [boot.util      :as util]
   [boot.parallel  :as parallel]
   [boot.from.io.aviso.exception :as ex])
  (:import
   [java.lang InterruptedException]
   [java.util Map Collections]
   [java.util.concurrent ConcurrentHashMap]))

(defn- init-sync-map
  "The (String) keys added here:
  - test-summaries - ConcurrentHashMap - for starting the computation"
  [sync-map]
  (doto sync-map
    (.put "test-summaries" (ConcurrentHashMap.))))

(defn- task-sync-map
  "Generates the (immutable) per test sync map."
  [sync-map]
  (assert (get sync-map "command-str")
          "The command-str key needs to be present. This is a bug.")
  (Collections/unmodifiableMap
   (let [command-str (get sync-map "command-str")
         summaries (doto (get sync-map "test-summaries")
                     (.put command-str (Collections/emptyMap)))]
     (doto (.clone sync-map)
       (.put "test-summaries" summaries)))))

(defn print-summary!
  "Print out (info) the summary. Note that it has to be in Clojure form
  already, see clojurize-summary."
  [summary]
  (util/info "Ran %s tests, %s assertions, %s failures, %s errors.\n"
             (get summary :test 0)
             (+ (get summary :pass 0) (get summary :fail 0) (get summary :error 0))
             (get summary :fail 0)
             (get summary :error 0)))

(defn clojurize-summaries
  "Transform Java to Clojure summaries, for instance converting keys to
  keywords. Returns a map of summaries indexed by command string (the
  boot in boot command executed, see command-str)."
  [^Map java-summaries]
  (assert (every? (partial instance? Map) (vals java-summaries)))
  (zipmap (keys java-summaries)
          (map (comp walk/keywordize-keys (partial into {})) (vals java-summaries))))

(defn merge-summaries
  "Merge summaries. Note that it has to be in Clojure form
  already, see clojurize-summary."
  [summaries]
  (assert (every? map? summaries))
  (apply merge-with + summaries))

(defn summary-errors
  "Retrieve the number or errors in a summary. Note that it has to be in
  Clojure form already, see clojurize-summary."
  [summary]
  (reduce (fnil + 0 0) ((juxt :fail :error) summary)))

(defn set-summary-data!
  "Set the test summary in the (shared) sync map for this pod"
  [data]
  (let [summaries (get data "test-summaries")
        command (get data "command-str")]
    (.put summaries command (Collections/unmodifiableMap
                             (walk/stringify-keys @test/*report-counters*)))))

(core/deftask testing-context
  []
  (fn [next-handler]
    (fn [fileset]
      (let [command-str (get pod/data "command-str")]
        (binding [test/*testing-contexts* (conj test/*testing-contexts* command-str)]
          (next-handler fileset))))))

(core/deftask inc-test-counter
  []
  (core/with-pass-thru [_]
    (test/inc-report-counter :test)))

(core/deftask with-report-counters
  []
  (fn [next-handler]
    (fn [fileset]
      (binding [test/*report-counters* (ref test/*initial-report-counters*)]
        (next-handler fileset)))))

(core/deftask set-summary
  []
  (core/with-pass-thru [_]
    (set-summary-data! pod/data)))

(defn test-task
  "Create a boot test task by wrapping other tasks, either the result of
  deftask or comp. Another way to say it is that a boot middleware
  should be passed here."
  [task]
  (let [command-str (get pod/data "command-str")]
    (try
      (util/dbug "In task %s\n" command-str)
      (comp (with-report-counters)
            (testing-context)
            (inc-test-counter)
            task
            (set-summary))
      (catch Throwable e
        ;; TODO, do I need to pass :actual in the return? Like in clojure.test:
        ;; https://github.com/clojure/clojure/tree/clojure-1.7.0/src/clj/clojure/test.clj#L532
        (test/inc-report-counter :error)
        (throw (ex-info "Exception while testing task" {:command-str command-str} e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;   deftesttask macro   ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-body
  "Given the split vector returned by split-deftask-forms, rebuild the
  correct forms for the deftask macro, replacing body with the one
  returned by (f old-body)."
  [forms f]
  (concat (butlast forms) (list (f (last forms)))))

(defmacro deftesttask
  "Define a test task. It enhances deftask so that tests can be run in
  parallel through boot.built-in/runtests.

  Inside a task you can combine the clojure.test facilities testing, is,
  are, ... the only requirement is that your deftesttask follows boot's
  middleware pattern and return either the result of comp-ositing tasks
  or identity.

  Example of declaration:

  (deftesttask my-test
   \"Testing.\"
    (with-pass-thru fs
      (testing \"whatever\"
        (is true \"Whatever must be true\"))))

  When clojure.test/*load-tests* is false, deftesttask is ignored."
  [& forms]
  (when test/*load-tests*
    (let [new-forms (-> forms
                        (boot.test/update-body #(cons 'boot.test/test-task (list %))))]
      `(do (core/deftask ~@new-forms) (alter-meta! (var ~(first new-forms)) assoc ::test-task ::test-me)))))

;;;;;;;;;;;;;;;;;;
;;  Test Vars   ;;
;;;;;;;;;;;;;;;;;;

(def test-me-pred
  "Return true when boot.test/test-task meta on the var is
  boot.test/test-me."
  (fn [var] (= ::test-me (::test-task (meta var)))))

(defn var->command
  "Return a string representing the task command to trigger (needed in
  runboot)."
  [var]
  (let [[ns name] (-> var meta ((juxt :ns :name)))]
    (str ns "/" name)))

(defn namespaces->vars
  "Filter the input namespaces in order to get the test task vars
  according to pred, a 1-arity function that accepts a var in input and
  returns true it is to be considered a test var.

  TODO: use transduce when switching Clojure 1.7.0"
  [pred namespaces]
  (->> namespaces
       (map ns-interns) ;; seq of maps
       (map vals)
       (reduce concat)
       (filter pred)))

;;;;;;;;;;;;;;;;;;
;;  Public API  ;;
;;;;;;;;;;;;;;;;;;

(core/deftask test-exit
  "Adds the ability to exit from the test with the right code, it simply parses
  the input summaries and call boot.util/exit-errors in case of failures or
  errors.

  You can omit this task if you don't care about having the return code not
  equal to zero (for instance during continuous testing in development)."
  []
  (core/with-pass-thru [_]
    (let [errors (-> pod/data
                     (get "test-summaries")
                     clojurize-summaries
                     vals
                     merge-summaries
                     summary-errors)]
      (if (> errors 0)
        (util/exit-error (util/dbug "Tests have %s failures/errors, error exiting...\n" errors))
        (util/dbug "Tests are passing, exiting normally...\n")))))

(core/deftask test-report
  "Boot's default test report task, it prints out the final summary.

  The usual -v/-vv control the verbosity of the report."
  []
  (core/with-pass-thru [_]
    (let [summaries (-> pod/data
                        (get "test-summaries")
                        (clojurize-summaries))]
      ;; Individual report
      (doseq [[command-str summary] summaries]
        (when (or (>= @util/*verbosity* 2)
                  (> (summary-errors summary) 0))
          (util/info "\n* boot %s\n" command-str)
          (print-summary! summary)))
      ;; Summary
      (util/info "\nSummary\n")
      (print-summary! (merge-summaries (vals summaries))))))

(core/deftask runtests
  "Run boot-in-boot parallel tests and collect the results.

  The default, no argument variant runs all the test tasks found in all the
  namespaces on the classpath, see boot.test/deftesttask on how to create test
  tasks.

  If you want more control, there actually is a command mode and a namespace
  mode. To avoid clashing they are mutually exclusive, command mode takes
  precedence.

  A command is the string you would use on the command line for running the
  task (after having it required in build.boot).

  The namespaces option, instead, is self-explanatory and does not allow to
  specify additionally arguments to tasks.

  Usage example at the command line:

      $ boot runtests -c \"foo-tests --param --int 5\" -c \"bar-tests\"

  Or in build.boot:
      (runtests :commands #{\"foo-tests :param true :int 5\"
                            \"bar-test\"})

  Last but not least, :threads is an integer that limits the number of spawned
  threads, defaulting to the canonical (-> (number of processors) inc inc)."
  [c commands   CMDS      ^:! #{str} "The boot task cli calls + arguments (a set of strings)."
   t threads    NUMBER        int    "The maximum number of threads to spawn during the tests."
   n namespaces NAMESPACE     #{sym} "The set of namespace symbols to run tests in."
   e exclusions NAMESPACE     #{sym} "The set of namespace symbols to be excluded from test."]
  (let [commands (cond
                   commands commands
                   :default (->> (or (seq namespaces) (all-ns))
                                 (remove (or exclusions #{}) )
                                 (namespaces->vars test-me-pred)
                                 (map var->command)))]
    (if (seq commands)
      (do (assert (every? string? commands)
                  (format "commands must be strings, got %s" commands))
          (binding [parallel/*parallel-hooks* {:init init-sync-map
                                               :task-init task-sync-map}]
            (parallel/runcommands :commands commands
                                  :batches threads)))
      (do (util/warn "No namespace was tested.")
          identity))))

(comment
  (reset! util/*verbosity* 2)
  (boot.core/boot (comp (runparallel :commands #{"boot.task.built-in-test/sift-add-meta-tests"
                                                 "boot.task.built-in-test/sift-to-asset-invert-tests"})
                        (test-report)
                        (test-exit)))

  (boot.core/boot (comp (runtests :threads 2
                                  :commands #{"boot.task.built-in-test/sift-add-meta-tests"
                                              "boot.task.built-in-test/sift-to-asset-invert-tests"
                                              "boot.task.built-in-test/sift-include-tests"})
                        (test-report)
                        (test-exit)))
  )
