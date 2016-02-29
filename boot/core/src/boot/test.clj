(ns boot.test
  (:require [clojure.string :as string]
            [clojure.walk   :as walk]
            [clojure.test   :as test]
            [boot.core      :as core]
            [boot.pod       :as pod]
            [boot.util      :as util])
  (:import java.lang.InterruptedException
           java.util.Map
           java.util.HashMap
           java.util.Collections
           java.util.concurrent.ConcurrentHashMap
           java.util.concurrent.CountDownLatch))

;; test helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn command-str
  "Given a seq of command segments, the input to the runboot task,
  return its string representation. Useful for Java interop, for example
  it can be key in a HashMap."
  [command-seq]
  (string/join " " command-seq))

(defn initial-sync-map
  "Generates the (mutable) sync map necessary for the test task.
  This map will be modified during parallel testing so make sure the
  content is either immutable or thread safe.
  The parameter test-commands is a set of sequences of "
  [test-commands]
  (let [start-latch (CountDownLatch. 1)
        done-latch (CountDownLatch. (count test-commands))]
    (doto (HashMap.)
      (.put "start-latch" start-latch)
      (.put "done-latch" done-latch)
      (.put "test-number" (count test-commands))
      (.put "test-command-str" nil) ;; AR - set in per-task-sync-map cause on a per-test basis
      (.put "test-summaries" (ConcurrentHashMap.)))))

(defn per-task-sync-map
  "Generates the (immutable) per test sync map. The map content will be
  modified during parallel testing so make sure that the mutable parts
  are thread safe."
  [sync-map command-seq]
  (assert (sequential? command-seq)
          "The command needs to be in the form [\"task\" \"param1\" \"param2\"]. This is a bug.")
  (Collections/unmodifiableMap
   (let [command-str (command-str command-seq)]
     (doto (.clone sync-map)
       (.put "test-command-str" command-str)
       #(-> (get % "test-summaries")
            (.put command-str (Collections/emptyMap)))))))

(core/deftask await-done
  [d data OBJECT ^:! code "The data for this task"]
  (core/with-pass-thru [fs]
    (util/dbug "Main thread is going to wait for test to complete...\n")
    (.await (get data "done-latch"))))

(core/deftask parallel-start
  [d data OBJECT ^:! code "The data for this task"]
  (core/with-pass-thru [fs]
    (util/info "Launching %s parallel tests...\n" (get data "test-number"))
    (.countDown (get data "start-latch"))))

(defn print-summary!
  "Print out (info) the summary. Note that it has to be in Clojure form
  already, see clojurize-summary."
  [summary]
  ;; (util/dbug "%s" (into {} summary))
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

(comment
  (def m (doto (HashMap.)
           (.put "a" {"test" 2 "pass" 1 "error" 1})
           (.put "b" {})
           (.put "c" {"test" 1 "fail" 1})))
  (print-summary! (second (vals (clojurize-summaries m))))
  (print-summary! (merge-summaries (vals (clojurize-summaries m)))))

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

(defn done!
  "Signal that this pod is shutting down"
  [data]
  (let [done-latch (get data "done-latch")]
    (.countDown done-latch)
    (util/dbug "Remaining running tests %s\n" (.getCount done-latch))))

(defn set-summary-data!
  "Set the summary in the (share) data for this pod"
  [data]
  (let [summaries (get data "test-summaries")
        command (get data "test-command-str")]
    (.put summaries command (Collections/unmodifiableMap
                             (walk/stringify-keys @test/*report-counters*)))))

(core/deftask testing-context
  []
  (fn [next-handler]
    (fn [fileset]
      (let [test-command-str (get pod/data "test-command-str")]
        (binding [test/*testing-contexts* (conj test/*testing-contexts* test-command-str)]
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
  ;; TODO - deftesttask
  (pod/add-shutdown-hook! #(done! pod/data))
  (.await (get pod/data "start-latch"))
  (try
    (util/dbug "In task %s\n" (get pod/data "test-command-str"))
    (comp (with-report-counters)
          (testing-context)
          (inc-test-counter)
          task
          (set-summary))
    (catch InterruptedException e
      (util/warn "Thread interrupted: %s\n" (.getMessage e))
      (test/inc-report-counter :error))
    (catch Throwable e
      (test/inc-report-counter :error)
      ;; TODO, do I need to pass :actual in the return?
      ;; (do-report {:type :error, :message "Uncaught exception, not in assertion."
      ;; :expected nil, :actual e})
      )))

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

  When *load-tests* is false, deftesttask is ignored."
  [& forms]
  (when test/*load-tests*
    (let [new-forms (-> forms
                        (boot.test/update-body #(cons 'boot.test/test-task (list %))))]
      `(alter-meta! (core/deftask ~@new-forms) assoc ::test-task ::test-me))))

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
