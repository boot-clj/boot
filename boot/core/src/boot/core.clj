(ns boot.core
  "The boot core API."
  (:require
   [clojure.java.io             :as io]
   [clojure.set                 :as set]
   [clojure.walk                :as walk]
   [clojure.repl                :as repl]
   [clojure.string              :as string]
   [boot.pod                    :as pod]
   [boot.git                    :as git]
   [boot.cli                    :as cli2]
   [boot.file                   :as file]
   [boot.tmpregistry            :as tmp]
   [boot.util                   :as util]
   [boot.from.clojure.tools.cli :as cli])
  (:import
   [java.net URLClassLoader URL]
   java.lang.management.ManagementFactory
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(declare temp-dir watch-dirs sync! on-env! get-env set-env!)

(declare ^{:dynamic true :doc "The running version of boot app."}      *app-version*)
(declare ^{:dynamic true :doc "The running version of boot core."}     *boot-version*)
(declare ^{:dynamic true :doc "Command line options for boot itself."} *boot-opts*)
(declare ^{:dynamic true :doc "Count of warnings during build."}       *warnings*)

;; Internal helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private tmpregistry       (atom nil))
(def ^:private cleanup-fns       (atom []))
(def ^:private boot-env          (atom nil))

(def ^:private src-watcher       (atom (constantly nil)))
(def ^:private user-src-paths    (atom #{}))
(def ^:private user-rsc-paths    (atom #{}))

(def ^:private tmp-user-src-dir  (atom nil))
(def ^:private tmp-user-rsc-dir  (atom nil))
(def ^:private tmp-src-dirs      (atom #{}))
(def ^:private tmp-rsc-dirs      (atom #{}))
(def ^:private tmp-scoped-dirs   (atom #{}))
(def ^:private tmp-restored-dirs (atom #{}))
(def ^:private tmp-cache-dirs    (atom #{}))

(def ^:private backup-dirs       (atom {}))

(defn- temp-dir!
  [& [key]]
  (tmp/mkdir! @tmpregistry (or key (keyword "boot.core" (str (gensym))))))

(defn- sync-user-dirs!
  []
  (when-not (empty? @user-src-paths)
    (apply file/sync :time @tmp-user-src-dir @user-src-paths))
  (when-not (empty? @user-rsc-paths)
    (apply file/sync :time @tmp-user-rsc-dir @user-rsc-paths)))

(defn- set-user-dirs!
  [type dirs]
  (@src-watcher)
  (-> (case type
        :src user-src-paths
        :rsc user-rsc-paths)
    (swap! into dirs))
  (sync-user-dirs!)
  (->> (set/union @user-src-paths @user-rsc-paths)
    (watch-dirs (fn [_] (sync-user-dirs!)))
    (reset! src-watcher)))

(defn- backup-key
  [dir]
  (->> dir .getPath munge keyword))

(defn- backup-dir!
  [dir]
  (when-not (get @backup-dirs dir)
    (let [key (backup-key dir)
          bak (or (temp-dir key) (temp-dir! key))]
      (sync! bak dir)
      (swap! backup-dirs assoc dir bak))))

(defn- restore-backups!
  []
  (doseq [[orig backup] @backup-dirs]
    (file/sync :time orig backup))
  (reset! backup-dirs {})
  (sync-user-dirs!))

(defn- do-cleanup!
  []
  (doseq [f @cleanup-fns] (util/guard (f)))
  (reset! cleanup-fns []))

(defn- printable-readable?
  "FIXME: document"
  [form]
  (or (nil? form) (false? form) (try (read-string (pr-str form)) (catch Throwable _))))

(defn- rm-clojure-dep
  "FIXME: document"
  [deps]
  (vec (remove (comp (partial = 'org.clojure/clojure) first) deps)))

(defn- add-dependencies!
  "Add Maven dependencies to the classpath, fetching them if necessary."
  [old new env]
  (->> new rm-clojure-dep (assoc env :dependencies) pod/add-dependencies)
  new)

(defn- add-directories!
  "Add URLs (directories or jar files) to the classpath."
  [dirs]
  (doseq [dir dirs] (pod/add-classpath dir)))

(defn- configure!*
  "Performs side-effects associated with changes to the env atom. Boot adds this
  function as a watcher on it."
  [old new]
  (doseq [k (set/union (set (keys old)) (set (keys new)))]
    (let [o (get old k ::noval)
          n (get new k ::noval)]
      (if (not= o n) (on-env! k o n new)))))

(defn- add-wagon!
  "FIXME: document this."
  ([maven-coord scheme-map]
     (add-wagon! nil [maven-coord] (get-env) scheme-map))
  ([old new env]
     (add-wagon! old new env nil))
  ([old new env scheme-map]
     (doseq [maven-coord new]
       (pod/call-worker
         `(boot.aether/add-wagon ~env ~maven-coord ~scheme-map)))
     new))

(defn- order-set-env-keys
  "FIXME: document"
  [kvs]
  (let [dk :dependencies]
    (->> kvs (sort-by first #(cond (= %1 dk) 1 (= %2 dk) -1 :else 0)))))

(defn- parse-task-opts
  "FIXME: document"
  [argv spec]
  (loop [opts [] [car & cdr :as argv] argv]
    (if-not car
      [opts argv]
      (let [opts* (conj opts car)
            parsd (cli/parse-opts opts* spec :in-order true)]
        (if (seq (:arguments parsd)) [opts argv] (recur opts* cdr))))))

(defmacro ^:private daemon
  [& body]
  `(doto (Thread. (fn [] ~@body)) (.setDaemon true) .start))

(defn- temp-dir-for
  [dir-or-file & dirsets]
  (->> dirsets
    (apply set/union)
    (some #(and (file/parent? (io/file %) (io/file dir-or-file)) %))))

(defn- temp-files-for
  [& dirsets]
  (->> dirsets
    (apply set/union)
    (mapcat file-seq)
    (filter (memfn isFile))))

;; Public API, temp dirs ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tmpfile?
  "Returns truthy if the file f is a tmpfile managed by the tmpregistry."
  [f]
  (tmp/tmpfile? @tmpregistry f))

(defn user-dirs      []            #{@tmp-user-rsc-dir @tmp-user-src-dir})
(defn source-dirs    []            (conj @tmp-src-dirs @tmp-user-src-dir))
(defn resource-dirs  []            (conj @tmp-rsc-dirs @tmp-user-rsc-dir))
(defn scoped-dirs    []            @tmp-scoped-dirs)
(defn restored-dirs  []            @tmp-restored-dirs)
(defn source-dir?    [dir-or-file] (temp-dir-for dir-or-file (source-dirs)))
(defn resource-dir?  [dir-or-file] (temp-dir-for dir-or-file (resource-dirs)))
(defn scoped-dir?    [dir-or-file] (temp-dir-for dir-or-file (scoped-dirs)))
(defn restored-dir?  [dir-or-file] (temp-dir-for dir-or-file (restored-dirs)))
(defn source-files   []            (temp-files-for (source-dirs)))
(defn resource-files []            (temp-files-for (resource-dirs)))
(defn scoped-files   []            (temp-files-for (scoped-dirs)))
(defn restored-files []            (temp-files-for (restored-dirs)))

(defn temp-dir
  "Given a key, returns the associated temp dir if one already exists."
  [key]
  (let [d (tmp/get @tmpregistry key)]
    (when (.exists d) d)))

(defn cache-dir!
  "Create a temp dir that is not on the classpath and is neither scoped nor
  restored. The files in these directories will not be part of the final
  product of the build. These directories are for tasks to use as private
  cache and should not be exposed to other tasks."
  [& [key]]
  (util/with-let [f (temp-dir! key)]
    (swap! tmp-cache-dirs conj f)))

(defn source-dir!
  "Create a temp dir that is on the classpath and is either scoped or restored
  according to the scoped? argument. The files in these directories will not
  be part of the final product of the build; they are intended ONLY as input
  for further processing."
  [& [scoped? key]]
  (util/with-let [f (temp-dir! key)]
    (swap! tmp-src-dirs conj f)
    (swap! (if scoped? tmp-scoped-dirs tmp-restored-dirs) conj f)
    (set-env! :directories #(conj % (.getPath f)))))

(defn resource-dir!
  "Create a temp dir that is on the classpath and is either scoped or restored
  according to the scoped? argument. The files in these directories will be
  part of the final product of the build; they MAY also be used as input for
  further processing."
  [& [scoped? key]]
  (util/with-let [f (temp-dir! key)]
    (swap! tmp-rsc-dirs conj f)
    (swap! (if scoped? tmp-scoped-dirs tmp-restored-dirs) conj f)
    (set-env! :directories #(conj % (.getPath f)))))

(defn delete-file!
  "Remove the given Files, fs, from the build fileset. The files must be temp
  files managed by boot. The difference between this and just using the .delete
  method of the File object is that this function correctly handles backing up
  temp dirs which were marked by their owner as being immutable."
  [& fs]
  (doseq [f fs]
    (assert (tmpfile? f) (format "can only delete temp files (%s)" f))
    (when-let [d (restored-dir? f)] (backup-dir! d))
    (.delete f)))

(defn relative-path
  "Get the path of a source file relative to the source directory it's in."
  [f]
  (->> (set/union @tmp-src-dirs @tmp-rsc-dirs)
    (map #(.getPath (file/relative-to (io/file %) (io/file f))))
    (some #(and (not= f (io/file %)) (util/guard (io/as-relative-path %)) %))))

(defn resource-path
  "Like relative-path but with forward slash (/) as path separator."
  [f]
  (->> f relative-path file/split-path (string/join "/")))

(defn sync!
  "Given a dest directory and one or more srcs directories, overlays srcs on
  dest, removing files in dest that are not in srcs. Uses file modification
  timestamps to decide which version of files to emit to dest. Uses hardlinks
  instead of copying file contents. File modification times are preserved.

  The zero-arg arity is used internally by boot."
  ([dest & srcs] (apply file/sync :time dest srcs))
  ([] (apply file/sync :time (get-env :tgt-path) (resource-dirs))))

;; ## Boot Environment
;;
;; _These functions are used internally by boot and are not part of the public
;; API._

(defn watch-dirs
  "Watches dirs for changes and calls callback with set of changed files
  when file(s) in these directories are modified. Returns a thunk which
  will stop the watcher.

  The watcher uses the somewhat quirky native filesystem event APIs. A
  debounce option is provided (in ms, default 10) which can be used to
  tune the watcher sensitivity."
  [callback dirs & {:keys [debounce]}]
  (if (empty? dirs)
    (constantly true)
    (do (pod/require-in-pod @pod/worker-pod "boot.watcher")
        (let [q        (LinkedBlockingQueue.)
              watchers (map file/make-watcher dirs)
              paths    (into-array String dirs)
              k        (.invoke @pod/worker-pod "boot.watcher/make-watcher" q paths)]
          (daemon
            (loop [ret (util/guard [(.take q)])]
              (when ret
                (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
                  (recur (conj ret more))
                  (let [changed (->> (map #(%) watchers)
                                  (reduce (partial merge-with set/union)) :time set)]
                    (when-not (empty? changed) (callback changed))
                    (recur (util/guard [(.take q)])))))))
          #(.invoke @pod/worker-pod "boot.watcher/stop-watcher" k)))))

(defn init!
  "Initialize the boot environment. This is normally run once by boot at
  startup. There should be no need to call this function directly."
  []
  (->> (io/file ".boot" "tmp") tmp/registry tmp/init! (reset! tmpregistry))
  (doto boot-env
    (reset! {:dependencies []
             :directories  #{}
             :src-paths    #{}
             :rsc-paths    #{}
             :tgt-path     "target"
             :repositories [["clojars"       "http://clojars.org/repo/"]
                            ["maven-central" "http://repo1.maven.org/maven2/"]]})
    (add-watch ::boot #(configure!* %3 %4)))
  (reset! tmp-user-src-dir (source-dir! false))
  (reset! tmp-user-rsc-dir (resource-dir! false))
  (pod/add-shutdown-hook! do-cleanup!))

(defmulti on-env!
  "Event handler called when the boot atom is modified. This handler is for
  performing side-effects associated with maintaining the application state in
  the boot atom. For example, when `:src-paths` is modified the handler adds
  the new directories to the classpath."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod on-env! ::default    [key old new env] nil)
(defmethod on-env! :directories [key old new env] (add-directories! new))
(defmethod on-env! :src-paths   [key old new env] (set-user-dirs! :src new))
(defmethod on-env! :rsc-paths   [key old new env] (set-user-dirs! :rsc new))

(defmulti merge-env!
  "This function is used to modify how new values are merged into the boot atom
  when `set-env!` is called. This function's result will become the new value
  associated with the given `key` in the boot atom."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod merge-env! ::default     [key old new env] new)
(defmethod merge-env! :directories  [key old new env] (set/union old new))
(defmethod merge-env! :wagons       [key old new env] (add-wagon! old new env))
(defmethod merge-env! :dependencies [key old new env] (add-dependencies! old new env))

;; ## Boot API Functions
;;
;; _Functions provided for use in boot tasks._

;; ## Boot Environment Management Functions

(defn get-env
  "Returns the value associated with the key `k` in the boot environment, or
  `not-found` if the environment doesn't contain key `k` and `not-found` was
  given. Calling this function with no arguments returns the environment map."
  [& [k not-found]]
  (if k (get @boot-env k not-found) @boot-env))

(defn set-env!
  "Update the boot environment atom `this` with the given key-value pairs given
  in `kvs`. See also `on-env!` and `merge-env!`. The values in the env map must
  be both printable by the Clojure printer and readable by its reader. If the
  value for a key is a function, that function will be applied to the current
  value of that key and the result will become the new value (similar to how
  clojure.core/update-in works."
  [& kvs]
  (doseq [[k v] (order-set-env-keys (partition 2 kvs))]
    (let [v (if-not (fn? v) v (v (get-env k)))]
      (assert (printable-readable? v)
        (format "value not readable by Clojure reader\n%s => %s" (pr-str k) (pr-str v)))
      (swap! boot-env update-in [k] (partial merge-env! k) v @boot-env))))

;; ## Defining Tasks

(defmacro deftask
  "Define a boot task."
  [sym doc argspec & body]
  `(cli2/defclifn ~(vary-meta sym assoc ::task true) ~doc ~argspec ~@body))

;; ## Boot Lifecycle

(defmacro cleanup
  "Evaluate body after tasks have been run. This macro is meant to be called
  from inside a task definition, and is provided as a means to shutdown or
  clean up persistent resources created by the task (eg. threads, files, etc.)"
  [& body]
  `(swap! @#'boot.core/cleanup-fns conj (fn [] ~@body)))

(defn make-event
  "Creates a new event map with base info about the build event. If the `event`
  argument is given the new event map is merged into it. This event map is what
  is passed down through the handler functions during the build."
  ([] (make-event {}))
  ([event] (merge event {:id (gensym) :time (System/currentTimeMillis)})))

(defn prep-build!
  "FIXME: document"
  [& args]
  (reset! *warnings* 0)
  (restore-backups!)
  (doseq [f @tmp-scoped-dirs] (tmp/make-file! ::tmp/dir f)) ; empty scoped dirs
  (apply make-event args))

(defn construct-tasks
  "FIXME: document"
  [& argv]
  (loop [ret [] [op-str & args] argv]
    (if-not op-str
      (apply comp (filter fn? ret))
      (let [op (-> op-str symbol resolve)]
        (when-not (and op (:boot.core/task (meta op)))
          (throw (IllegalArgumentException. (format "No such task (%s)" op-str))))
        (let [spec   (:argspec (meta op))
              parsed (cli/parse-opts args spec :in-order true)]
          (when (seq (:errors parsed))
            (throw (IllegalArgumentException. (string/join "\n" (:errors parsed)))))
          (let [[opts argv] (parse-task-opts args spec)]
            (recur (conj ret (apply (var-get op) opts)) argv)))))))

(defn run-tasks
  "FIXME: document"
  [task-stack]
  (binding [*warnings* (atom 0)]
    ((task-stack #(do (sync!) %)) (prep-build!))))

(defmacro boot
  "Builds the project as if `argv` was given on the command line."
  [& argv]
  (let [->list #(cond (seq? %) % (vector? %) (seq %) :else (list %))
        ->app  (fn [xs] `(apply comp (filter fn? [~@xs])))]
    `(try @(future
             (run-tasks
               ~(if (every? string? argv)
                  `(apply construct-tasks [~@argv])
                  (->app (map ->list argv)))))
          (finally (#'do-cleanup!)))))

;; ## Low-Level Tasks / Task Helpers

(def ^:dynamic *event*    nil)

(defn pre-wrap
  "This task applies `f` to the event map and any `args`, and then passes the
  result to its continuation."
  [f & args]
  (fn [continue]
    (fn [event]
      (continue (apply f event args)))))

(defmacro with-pre-wrap
  "Emits a task wherein `body` expressions are evaluated for side effects before
  calling the continuation."
  [& body]
  `(fn [continue#]
     (fn [event#]
       (binding [*event* event#]
         ~@body)
       (continue# event#))))

(defn post-wrap
  "This task calls its continuation and then applies `f` to it and any `args`,
  returning the result."
  [f & args]
  (fn [continue]
    (fn [event]
      (apply f (continue event) args))))

(defmacro with-post-wrap
  "Emits a task wherein `body` expressions are evaluated for side effects after
  calling the continuation."
  [& body]
  `(fn [continue#]
     (fn [event#]
       (continue# event#)
       (binding [*event* event#]
         ~@body))))

;; ## Task Configuration Macros

(defmacro replace-task!
  "Given a number of binding form and function pairs, this macro alters the
  root bindings of task vars, replacing their values with the given functions.

  Example:

  (replace-task!
    [r repl] (fn [& xs] (apply r :port 12345 xs))
    [j jar]  (fn [& xs] (apply j :manifest {\"howdy\" \"world\"} xs)))"
  [& replacements]
  `(do ~@(for [[[bind task] expr] (partition 2 replacements)]
           `(alter-var-root (var ~task) (fn [~bind] ~expr)))))

(defmacro disable-task!
  "Disables the given tasks by replacing them with the identity task.

  Example:

  (disable-task! repl jar)"
  [& tasks]
  `(do ~@(for [task tasks]
           `(replace-task! [t# ~task] (fn [& _#] identity)))))

(defmacro task-options!
  "Given a number of task/vector-of-curried-arguments pairs, replaces the root
  bindings of the tasks with their curried values.

  Example:

  (task-options!
    repl [:port 12345]
    jar  [:manifest {:howdy \"world\"}])"
  [& task-option-pairs]
  `(do ~@(for [[task opts] (partition 2 task-option-pairs)]
           `(replace-task! [t# ~task]
              (fn [& xs#] (apply t# (concat ~opts xs#)))))))

;; ## Public Utility Functions

(defn json-generate
  "Same as cheshire.core/generate-string."
  [x & [opt-map]]
  (pod/call-worker
    `(cheshire.core/generate-string ~x ~opt-map)))

(defn json-parse
  "Same as cheshire.core/parse-string."
  [x & [key-fn]]
  (pod/call-worker
    `(cheshire.core/parse-string ~x ~key-fn)))

(defn yaml-generate
  "Same as clj-yaml.core/generate-string."
  [x]
  (pod/call-worker
    `(clj-yaml.core/generate-string ~x)))

(defn yaml-parse
  "Same as clj-yaml.core/parse-string."
  [x]
  (pod/call-worker
    `(clj-yaml.core/parse-string ~x)))

(defn touch
  "Same as the Unix touch(1) program."
  [f]
  (.setLastModified f (System/currentTimeMillis)))

(defn git-files [& {:keys [untracked]}]
  (git/ls-files :untracked untracked))

(defn file-filter
  "A file filtering function factory. FIXME: more documenting here."
  [mkpred]
  (fn [criteria files & [negate?]]
    ((if negate? remove filter)
     #(some identity ((apply juxt (map mkpred criteria)) (io/file %))) files)))

(defn by-name
  "This function takes two arguments: `names` and `files`, where `names` is
  a seq of file name strings like `[\"foo.clj\" \"bar.xml\"]` and `files` is 
  a seq of file objects. Returns a seq of the files in `files` which have file
  names listed in `names`."
  [names files & [negate?]]
  ((file-filter #(fn [f] (= (.getName f) %))) names files negate?))

(defn not-by-name
  "This function is the same as `by-name` but negated."
  [names files]
  (by-name names files true))

(defn by-ext
  "This function takes two arguments: `exts` and `files`, where `exts` is a seq
  of file extension strings like `[\".clj\" \".cljs\"]` and `files` is a seq of
  file objects. Returns a seq of the files in `files` which have file extensions
  listed in `exts`."
  [exts files & [negate?]]
  ((file-filter #(fn [f] (.endsWith (.getName f) %))) exts files negate?))

(defn not-by-ext
  "This function is the same as `by-ext` but negated."
  [exts files]
  (by-ext exts files true))

(defn by-re
  "This function takes two arguments: `res` and `files`, where `res` is a seq
  of regex patterns like `[#\"clj$\" #\"cljs$\"]` and `files` is a seq of
  file objects. Returns a seq of the files in `files` whose names match one of
  the regex patterns in `res`."
  [res files & [negate?]]
  ((file-filter #(fn [f] (re-find % (.getName f)))) res files negate?))

(defn not-by-re
  "This function is the same as `by-re` but negated."
  [res files]
  (by-re res files true))
