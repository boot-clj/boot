(ns boot.task-helpers
  (:require
    [clojure.java.io         :as io]
    [clojure.set             :as set]
    [clojure.string          :as string]
    [clojure.stacktrace      :as trace]
    [clojure.test            :as test]
    [clojure.walk            :as walk]
    [boot.from.io.aviso.ansi :as ansi]
    [boot.from.digest        :as digest]
    [boot.pod                :as pod]
    [boot.core               :as core]
    [boot.file               :as file]
    [boot.util               :as util]
    [boot.tmpdir             :as tmpd])
  (:import java.lang.InterruptedException
           [java.util Map HashMap Collections
                      concurrent.ConcurrentHashMap
                      concurrent.CountDownLatch]))

(defn- first-line [s] (when s (first (string/split s #"\n"))))

(defn- tasks-table [tasks]
  (let [get-task #(-> % :name str)
        get-desc #(-> % :doc first-line)
        built-in {nil (get tasks 'boot.task.built-in)}]
    (->> (dissoc tasks 'boot.task.built-in)
      (concat built-in) (interpose nil)
      (mapcat (fn [[_ xs]] (or xs [{:name "" :doc ""}])))
      (mapv (fn [x] ["" (get-task x) (get-desc x)])))))

(defn- set-title [[[_ & xs] & zs] title] (into [(into [title] xs)] zs))

(defn- available-tasks [sym]
  (let [base  {nil (the-ns sym)}
        task? #(:boot.core/task %)
        nssym #(->> % meta :ns ns-name)
        addrf #(if-not (seq %1) %2 (symbol %1 (str %2)))
        proc  (fn [a [k v]] (assoc (meta v) :ns* (nssym v) :name (addrf a k) :var v))
        pubs  (fn [[k v]] (map proc (repeat (str k)) (ns-publics v)))]
    (->>
      (concat
        (->> sym ns-refers (map proc (repeat nil)))
        (->> sym ns-aliases (into base) (mapcat pubs)))
      (filter task?) (sort-by :name) (group-by :ns*) (into (sorted-map)))))

(defn read-pass
  [prompt]
  (String/valueOf (.readPassword (System/console) prompt nil)))

(defn print-fileset
  [fileset]
  (letfn [(tree [xs]
            (when-let [xs (seq (remove nil? xs))]
              (->> (group-by first xs)
                   (reduce-kv #(let [t (->> %3 (map (comp seq rest)) tree)]
                                 (assoc %1 (if (map? t) (ansi/bold-blue %2) %2) t))
                              (sorted-map-by #(->> %& (map ansi/strip-ansi) (apply compare)))))))]
    (let [tmpfiles    (core/ls fileset)
          split-paths (map (comp file/split-path core/tmp-path) tmpfiles)]
      (util/print-tree [["" (into #{} (tree split-paths))]]))))

;; sift helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sift-match
  [invert? regexes]
  (fn [path tmpfile]
    ((if-not invert? identity not)
     (some #(re-find % path) regexes))))

(defn- sift-meta
  [invert? kws]
  (fn [path tmpfile]
    (some (comp (if-not invert? identity not)
                (partial contains? kws)) (keys tmpfile))))

(defn- sift-mv
  [rolekey invert? optargs]
  (fn [fileset]
    (let [match?  (sift-match invert? optargs)
          dir     (#'core/get-add-dir fileset #{rolekey})
          reducer (fn [xs k v]
                    (->> (if-not (match? k v) v (assoc v :dir dir))
                         (assoc xs k)))]
      (->> (partial reduce-kv reducer {})
           (update-in fileset [:tree])))))

(defn- sift-add
  [rolekey optargs]
  (fn [fileset]
    (let [dir (#'core/get-add-dir fileset #{rolekey})]
      (->> (map io/file optargs)
           (reduce #(tmpd/add %1 dir %2 nil) fileset)))))

(defn- sift-filter
  "Similar to filter, where match? is a predicate with signature [path tmpfile]."
  [match?]
  (let [reducer (fn [xs k v] (if-not (match? k v) xs (assoc xs k v)))]
    (fn [fileset] (->> (partial reduce-kv reducer {})
                      (update-in fileset [:tree])))))

(defn- jar-path
  [sym]
  (let [env (core/get-env)]
    (->> env
         :dependencies
         (filter #(= sym (first %)))
         first
         (pod/resolve-dependency-jar env))))

(defmulti  sift-action (fn [invert? opkey optargs] opkey))
(defmethod sift-action :to-asset     [v? _ args] (sift-mv  :asset    v? args))
(defmethod sift-action :to-resource  [v? _ args] (sift-mv  :resource v? args))
(defmethod sift-action :to-source    [v? _ args] (sift-mv  :source   v? args))
(defmethod sift-action :add-asset    [_  _ args] (sift-add :asset       args))
(defmethod sift-action :add-resource [_  _ args] (sift-add :resource    args))
(defmethod sift-action :add-source   [_  _ args] (sift-add :source      args))
(defmethod sift-action :include      [v? _ args] (sift-filter (sift-match v? args)))
(defmethod sift-action :with-meta    [v? _ args] (sift-filter (sift-meta  v? args)))

(defmethod sift-action :move
  [_ _ args]
  (let [proc    #(reduce-kv string/replace % args)
        reducer (fn [xs k v]
                  (let [k (proc k)]
                    (assoc xs k (assoc v :path k))))]
    (fn [fileset]
      (->> (partial reduce-kv reducer {})
           (update-in fileset [:tree])))))

(defmethod sift-action :add-jar
  [v? _ args]
  (fn [fileset]
    (-> (fn [fs [sym regex]]
          (let [incl (when-not v? [regex])
                excl (when v? [regex])
                jar  (jar-path sym)]
            (core/add-cached-resource
              fs (digest/md5 jar) (partial pod/unpack-jar jar)
              :include incl :exclude excl :mergers pod/standard-jar-mergers)))
        (reduce fileset args))))

(defmethod sift-action :add-meta
  [v? _ args]
  (fn [fileset]
    (let [[regex kw] (first args)
          file-paths (filter (comp (if v? not identity)
                                   (partial re-find regex)) (keys (:tree fileset)))]
      (core/add-meta fileset (zipmap file-paths
                                     (repeat {kw true}))))))

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
      ;; (.put "test-command") ;; AR - set in per-task-sync-map cause on a per-test basis
      (.put "test-summaries" (ConcurrentHashMap.)))))

(defn per-task-sync-map
  "Generates the (immutable) per test sync map. The map content will be
  modified during parallel testing so make sure that the mutable parts
  are thread safe."
  [sync-map command-seq]
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

(defn- print-summary!
  "Print out (info) the summary. Note that it has to be in Clojure form
  already, see clojurize-summary."
  [summary]
  ;; (util/dbug "%s" (into {} summary))
  (util/info "Ran %s tests, %s assertions, %s failures, %s errors.\n"
             (get summary :test 0)
             (+ (get summary :pass 0) (get summary :fail 0) (get summary :error 0))
             (get summary :fail 0)
             (get summary :error 0)))

(defn- clojurize-summaries
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

(defn- merge-summaries
  "Merge summaries. Note that it has to be in Clojure form
  already, see clojurize-summary."
  [summaries]
  (assert (every? map? summaries))
  (apply merge-with + summaries))

(defn- summary-errors
  "Retrieve the number or errors in a summary. Note that it has to be in
  Clojure form already, see clojurize-summary."
  [summary]
  (reduce (fnil + 0 0) ((juxt :fail :error) summary)))

(core/deftask test-report
  [d data OBJECT ^:! code "The data for this task"]
  (core/with-pass-thru [_]
    (let [summaries (clojurize-summaries (get data "test-summaries"))]
      ;; Individual report
      (doseq [[command-str summary] summaries]
        (util/info "\n* boot %s\n" command-str)
        (print-summary! summary))
      ;; Summary
      (util/info "\nSummary\n")
      (print-summary! (merge-summaries (vals summaries))))))

(core/deftask test-exit
  [d data OBJECT ^:! code "The data for this task"]
  (core/with-pass-thru [_]
    (let [errors (-> (get data "test-summaries")
                     clojurize-summaries
                     vals
                     merge-summaries
                     summary-errors)]
      (if (> errors 0)
        (util/exit-error (util/dbug "Tests have %s failures/errors, error exiting...\n" errors))
        (util/dbug "Tests are passing, exiting normally...\n")))))

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
