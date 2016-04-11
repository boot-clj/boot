(ns boot.parallel
  (:require
   [clojure.string :as string]
   [boot.core      :as core]
   [boot.pod       :as pod]
   [boot.util      :as util]
   [boot.from.io.aviso.exception :as ex])
  (:import
   [java.util HashMap Collections]
   [java.util.concurrent CountDownLatch TimeUnit]))

;; AR we might need also to modify the map when a task/batch/computation
;; finishes. However, I might overengineer a bit and therefore I am leaving
;; this as a comment for future patches:
;; - :task-end      called after ending a task
;; - :batch-end     called after a batch ends
;; - :end           called after the whole computation ends

(def ^{:dynamic true
       :doc "Bind this to a map of functions if you want to modify the
       so-called sync map, shared data among threads of execution, at various
       phases of the parallel computation.

All the functions will receive a mutable java.util.HashMap in input that will
be passed down in boot.pod/data. The values will be probably modified so be
sure that they are either thread safe or immutable.

No Clojure should be put in here, not even HashMap keys. The functions should
return THE SAME object in output. This is a necessary devil to face for correct
concurrent execution and it is due to the fact that Clojure data structures
cannot be passed around in pods.

There are six hooks, below are the keys, the default is identity:

{:init          ;; called before running the whole parallel computation
 :batch-init    ;; called before running a batch in parallel
 :task-init     ;; called before running a task}"}
  *parallel-hooks* {:init identity
                    :batch-init identity
                    :task-init identity})

(def ^{:dynamic true
       :doc "Parallel task execution timeout.

It follows CountDownLatch.await(long timeout, TimeUnit unit):
https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/CountDownLatch.html#await%28long,%20java.util.concurrent.TimeUnit%29

The first item of the vector is timeout, the second unit"}
  *parallel-timeout* [30 TimeUnit/MINUTES])

(core/deftask runboot
  "Run boot in boot.

  The args parameter is exactly the same kind of string you would write on the
  command line (split in a vector). Runboot will execute the tasks in a brand
  new pod every time it is invoked, isolating from the boot instance it is
  launched from.

  Data will be passed to the new pod (found in boot.pod/data). It has to be a
  Java object (pods might not share the same Clojure version and therefore
  Clojure objects won't work)."
  [a args ARG        [str] "The boot cli arguments."
   d data OBJECT ^:! code  "The data to pass the (new) boot environment"]
  (let [core   (boot.App/newCore data)
        worker (future pod/worker-pod)
        args   (-> (vec (remove empty? args))
                   (->> (into-array String)))]
    (util/dbug "Runboot will run %s \n" (str "\"boot " (string/join " " args) "\""))
    (core/with-pass-thru [fs]
      (future (try (.await (get pod/data "start-latch"))
                   (boot.App/runBoot core worker args)
                   (catch InterruptedException e
                     (util/print-ex e))
                   (catch Throwable e
                     (util/print-ex e))
                   (finally
                     (let [done-latch (get data "done-latch")]
                       (if (.countDown done-latch)
                         (util/dbug "All tasks completed\n")
                         (util/warn "Some parallel task timed out (execution time > %s seconds)\n"
                                    (.toSeconds (first *parallel-timeout*)))))))))))

(defn empty-sync-map
  "Create an empty sync-map (a HashMap).

  This is a necessary devil to face for correct concurrent execution and
  it is due to the fact that Clojure data structures cannot be passed
  around from pod to pod."
  []
  (HashMap.))

(defn batch-sync-map
  "Add batch-specific keys to the input sync map.

  This function currently clones, adds keys and returns a HashMap whose
  values will be modified during the parallel computation. Make sure
  they are all either immutable or thread safe.

  The (String) keys added here:
  - start-latch - CountDownLatch - for starting the computation
  - done-latch  - CountDownLatch - for waiting if it is done
  - task-number - Long           - for counting the executed tasks"
  [sync-map task-number]
  (let [start-latch (CountDownLatch. 1)
        done-latch (CountDownLatch. task-number)]
    (doto (.clone sync-map)
      (.put "start-latch" start-latch)
      (.put "done-latch" done-latch)
      (.put "task-number" task-number))))

(defn command-str
  "Given a seq of command segments, the input to the runboot task,
  return its string representation. Useful for Java interop, for example
  it can be key in a HashMap."
  [command-seq]
  (string/join " " command-seq))

(defn command-seq
  "The dual of boot.parallel/command-str. Return a command is the
  form: [\"task\" \"param1\" \"param2\"]."
  [command-str]
  (string/split command-str #"\s"))

(defn task-sync-map
  "Add batch-specific keys to the input sync map.

  This function currently clones, adds keys and returns a HashMap whose
  values will be modified during the parallel computation. Make sure
  they are all either immutable or thread safe.

  The (String) keys added here:
  - command-str - String - the command to be executed"
  [sync-map command-seq]
  (assert (sequential? command-seq)
          "The command needs to be in the form [\"task\" \"param1\" \"param2\"]. This is a bug.")
  (assert (nil? (get sync-map "command-str"))
          "The command-str needs to be nil, that is to signal that the sync map has been freshly initialized, otherwise there might be a bug")
  (let [command-str (command-str command-seq)]
    (doto (.clone sync-map)
      (.put "command-str" command-str))))

(core/deftask await-done
  [d data OBJECT ^:! code "The data for this task"]
  (core/with-pass-thru [fs]
    (let [latch (get data "done-latch")]
      (util/dbug "The main thread is going to wait for %s task(s) to complete...\n" (.getCount latch))
      (.await latch (first *parallel-timeout*) (second *parallel-timeout*)))))

(core/deftask parallel-start
  [d data OBJECT ^:! code "The data for this task"]
  (core/with-pass-thru [fs]
    (util/info "Launching %s parallel tasks...\n" (get data "task-number"))
    (.countDown (get data "start-latch"))))

(defn- runboot-reducer
  "Helper that given a sync-map returns a function that composes boot
  middlewares that will perform parallel tasks.

  This can be made more generic and recursively compose any task, not
  just runboot, in the future."
  [sync-map]
  (fn [middlewares command]
    (let [cmd-seq (command-seq command)
          task-init-fn (:task-init *parallel-hooks*)]
      (comp (runboot :args cmd-seq
                     :data (task-init-fn (task-sync-map sync-map cmd-seq)))
            middlewares))))

(defn- commands->parallel-task
  "Produce a task that will run each command in parallel, waiting for
  all of them to finish."
  [sync-map commands]
  (let [batch-init-fn (:batch-init *parallel-hooks*)
        sync-map (batch-init-fn (batch-sync-map sync-map (count commands)))]
    (pod/set-data! sync-map)
    (comp (reduce (runboot-reducer sync-map) identity commands)
          (parallel-start :data sync-map)
          (await-done :data sync-map))))

(core/deftask runcommands
  "Run commands using boot in boot, but in parallel.

  A command is the string you would use on the command line for running the
  task (after having it required in build.boot).

  If no batches number is specified, it spawns one thread per command (which
  can be quite system demanding, so be careful).

  Usage example at the command line:

      $ boot runcommands -c \"foo-task --param --int 5\" -c \"bar\"

  Or in build.boot:
      (runcommands :commands #{\"foo-task :param true :int 5\"
                               \"bar-task\"})

  Last but not least, :batches is an integer that defines the size of the task
  batch to run in parallel, therefore limiting the number of spawned threads,
  defaulting to the canonical (-> (number of processors) inc inc)."
  [c commands CMDS      ^:! #{str} "The boot task cli calls + arguments (a set of strings)."
   b batches  NUMBER        int    "The commands will be executed in parallel batch-number per time."]
  (let [n (or batches (-> (Runtime/getRuntime)
                          .availableProcessors
                          (+ 2)))
        parallel-init-fn (:init *parallel-hooks*)
        sync-map (parallel-init-fn (empty-sync-map))
        seqs-of-cmds (partition-all n commands)
        seqs-of-midwares (map #(commands->parallel-task sync-map %) seqs-of-cmds)]
    (util/dbug "Middleware command partitions: %s.\n" (vec seqs-of-cmds))
    (core/with-pre-wrap [fileset]
      (reduce (fn [prev-fs mw]
                (let [handler (mw identity)] ;; this triggers the parallel computation
                  (handler prev-fs)))
              fileset
              seqs-of-midwares))))
