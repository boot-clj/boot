(ns boot.core
  "The boot core API."
  (:require
   [clojure.java.io             :as io]
   [clojure.set                 :as set]
   [clojure.walk                :as walk]
   [clojure.repl                :as repl]
   [clojure.string              :as string]
   [boot.pod                    :as pod]
   [boot.cli                    :as cli2]
   [boot.file                   :as file]
   [boot.tmpregistry            :as tmp]
   [boot.util                   :as util]
   [boot.from.clojure.tools.cli :as cli])
  (:import
   [java.net URLClassLoader URL]
   java.lang.management.ManagementFactory))

(declare get-env set-env! boot-env on-env! merge-env! tgt-files relative-path)

;; ## Utility Functions
;;
;; _These functions are used internally by boot and are not part of the public API._

(def ^:private tmpregistry  (atom nil))

(defn- printable-readable?
  [form]
  (try (read-string (pr-str form)) (catch Throwable _)))

(defn- rm-clojure-dep
  [deps]
  (vec (remove (comp (partial = 'org.clojure/clojure) first) deps)))

(defn- add-dependencies!
  "Add Maven dependencies to the classpath, fetching them if necessary."
  [old new env]
  (->> new rm-clojure-dep (assoc env :dependencies) pod/add-dependencies)
  (into (or old []) new))

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

(defn- order-set-env-keys
  [kvs]
  (let [dk :dependencies]
    (->> kvs (sort-by first #(cond (= %1 dk) 1 (= %2 dk) -1 :else 0)))))

(def ^:private base-env
  "Returns initial boot environment settings."
  (fn []
    '{:dependencies []
      :src-paths    #{}
      :tgt-path     "target"
      :repositories [["clojars"       "http://clojars.org/repo/"]
                     ["maven-central" "http://repo1.maven.org/maven2/"]]}))

;; ## Boot Environment
;;
;; _These functions are used internally by boot and are not part of the public
;; API._

(declare ^{:dynamic true :doc "The running version of boot."} *boot-version*)
(declare ^{:dynamic true :doc "Command line options for boot itself."} *boot-opts*)

(def boot-env
  "Atom containing environment key/value pairs. Do not manipulate this atom
  directly. Use `set-env!` (below) instead."
  (atom nil))

(defn init!
  "Initialize the boot environment. This is normally run once by boot at startup.
  There should be no need to call this function directly."
  [& kvs]
  (doto boot-env
    (reset! (merge (base-env) (apply hash-map kvs)))
    (add-watch ::boot #(configure!* %3 %4))))

(defmulti on-env!
  "Event handler called when the boot atom is modified. This handler is for
  performing side-effects associated with maintaining the application state in
  the boot atom. For example, when `:src-paths` is modified the handler adds
  the new directories to the classpath."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod on-env! ::default  [key old new env] nil)
(defmethod on-env! :src-paths [key old new env] (add-directories! (set/difference new old)))

(defmulti merge-env!
  "This function is used to modify how new values are merged into the boot atom
  when `set-env!` is called. This function's result will become the new value
  associated with the given `key` in the boot atom."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod merge-env! ::default     [key old new env] new)
(defmethod merge-env! :src-paths    [key old new env] (into (or old #{}) new))
(defmethod merge-env! :dependencies [key old new env] (add-dependencies! old new env))

;; ## Boot API Functions
;;
;; _Functions provided for use in boot tasks._

;; Maven Repository Global Configuration

(defn set-offline!
  "Set/unset offline mode for dependency resolution."
  [x]
  (pod/call-worker `(boot.aether/set-offline! ~x)))

(defn set-update!
  "Set the snapshot update frequency for dependency resolution. Accepted values
  of x are `:always`, `:daily`, or `:never`."
  [x]
  (pod/call-worker `(boot.aether/set-update! ~x)))

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
      (assert (printable-readable? v) "value not readable by Clojure reader")
      (swap! boot-env update-in [k] (partial merge-env! k) v @boot-env))))

(defn add-sync!
  "Specify directories to sync after build event. The `dst` argument is the 
  destination directory. The `srcs` are an optional list of directories whose
  contents will be copied into `dst`. The `add-sync!` function is associative.

  Example:

    ;; These are equivalent:
    (add-sync! bar [baz baf])
    (do (add-sync! bar [baz]) (add-sync! bar [baf]))
  "
  [dst & [srcs]]
  (tmp/add-sync! @tmpregistry dst srcs))

;; ## Task helpers

(def ^:private consumed-files (atom #{}))

(defn consume-file!
  "FIXME: document"
  [& fs]
  (swap! consumed-files into fs))

(defn sync!
  "When called with no arguments it triggers the syncing of directories added
  via `add-sync!`. This is used internally by boot. When called with `dest` dir
  and a number of `srcs` directories it syncs files from the src dirs to the
  dest dir, overlaying them on top of each other.

  When called with no arguments directories will be synced only if there are
  artifacts in target directories to sync. If there are none `sync!` does
  nothing."
  ([]
     (let [tgtfiles (set (tgt-files))
           tgt      (io/file (get-env :tgt-path))]
       (when-not (empty? tgtfiles)
         (tmp/sync! @tmpregistry)
         (doseq [f @consumed-files :let [g (io/file tgt (relative-path f))]]
           (when (.exists g) (.delete g)))
         (doseq [d (->> tgt file-seq reverse)]
           (when (and (.isDirectory d) (not (seq (.listFiles d)))) (.delete d))))))
  ([dest & srcs]
     (apply file/sync :hash dest srcs)))

(defn tmpfile?
  "Returns truthy if the file f is a tmpfile managed by the tmpregistry."
  [f]
  (tmp/tmpfile? @tmpregistry f))

(defn mktmp!
  "Create a temp file and return its `File` object. If `mktmp!` has already 
  been called with the given `key` the tmpfile will be truncated. The optional
  `name` argument can be used to customize the temp file name (useful for
  creating temp files with a specific file extension, for example)."
  [key & [name]]
  (tmp/mk! @tmpregistry key name))

(defn mktmpdir!
  "Create a temp directory and return its `File` object. If `mktmpdir!` has
  already been called with the given `key` the directory's contents will be
  deleted. The optional `name` argument can be used to customize the temp
  directory name, as with `mktmp!` above."
  [key & [name]]
  (tmp/mkdir! @tmpregistry key name))

(def tgtdirs
  "Atom containing a vector of File objects--directories created by `mktgtdir!`.
  This atom is managed by boot and shouldn't be manipulated directly."
  (atom []))

(defn tgtdir?
  "Returns `f` if it was created by `mktgtdir!`, otherwise nil."
  [f]
  (when (contains? (set @tgtdirs) f) f))

(defn mktgtdir!
  "Create a tempdir managed by boot into which tasks can emit artifacts. See
  https://github.com/tailrecursion/boot#boot-managed-directories for more info."
  [key & [name]]
  (util/with-let [f (mktmpdir! key name)]
    (swap! tgtdirs conj f)
    (set-env! :src-paths #{(.getPath f)})
    (add-sync! (get-env :tgt-path) [(.getPath f)])))

(defn mksrcdir!
  "Create a tmpdir managed by boot into which tasks can emit artifacts which
  are constructed in order to be intermediate source files but not intended to
  be synced to the project `:tgt-path`. See https://github.com/tailrecursion/boot#boot-managed-directories
  for more info."
  [key & [name]]
  (util/with-let [f (mktmpdir! key name)]
    (set-env! :src-paths #{(.getPath f)})))

(defn unmk!
  "Delete the temp or target file or directory created with the given `key`."
  [key]
  (tmp/unmk! @tmpregistry key))

(defmacro deftask
  "Define a boot task."
  [sym doc argspec & body]
  `(cli2/defclifn ~(vary-meta sym assoc ::task true) ~doc ~argspec ~@body))

(defn make-event
  "Creates a new event map with base info about the build event. If the `event`
  argument is given the new event map is merged into it. This event map is what
  is passed down through the handler functions during the build."
  ([] (make-event {}))
  ([event] (merge event {:id (gensym) :time (System/currentTimeMillis)})))

(defn prep-build!
  "FIXME: document"
  [& args]
  (doseq [f @tgtdirs] (tmp/make-file! ::tmp/dir f))
  (reset! consumed-files #{})
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
          (recur (conj ret (apply (var-get op) args)) (:arguments parsed)))))))

(def ^:dynamic *warnings* nil)

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
    `(run-tasks ~(if (every? string? argv)
                   `(apply construct-tasks [~@argv])
                   (->app (map ->list argv))))))

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

(defn src-files
  "Returns a seq of `java.io.File` objects--the contents of directories in the
  :src-paths boot environment. Note that this includes the `tgt-files` below."
  []
  (let [want? #(and (.isFile %) (not (contains? @consumed-files %)))]
    (->> :src-paths get-env (map io/file) (mapcat file-seq) (filter want?) set)))

(defn tgt-files
  "Returns a seq of `java.io.File` objects--the contents of directories created
  by tasks via the `mktgtdir!` function above."
  []
  (let [want? #(and (.isFile %) (not (contains? @consumed-files %)))]
    (->> @tgtdirs (mapcat file-seq) (filter want?) set)))

#_(defn src-resources
  "Returns a seq of urls corresponding to resources in jars on the classpath.
  Regexes can be (optionally) provided to restrict the set to jar dependencies
  whose group-id/artifact-id symbol matches one of the regexes."
  [& regexes]
  (pod/call-worker
    `(boot.aether/jar-entries-in-dep-order ~(get-env) ~@regexes)))

(defn newer?
  "Given a seq of source file objects `srcs` and a number of `artifact-dirs`
  directory file objects, returns truthy when any file in `srcs` is newer than
  any file in any of the `artifact-dirs`."
  [srcs & artifact-dirs]
  (let [mod      #(.lastModified %)
        file?    #(.isFile %)
        smod     (->> srcs (filter file?) (map mod))
        omod     (->> artifact-dirs (mapcat file-seq) (filter file?) (map mod))
        missing? (not (and (seq smod) (seq omod)))]
    (when (or missing? (< (apply min omod) (apply max smod))) srcs)))

(defn relative-path
  "Get the path of a source file relative to the source directory it's in."
  [f]
  (->> (get-env :src-paths)
    (map #(.getPath (file/relative-to (io/file %) f)))
    (some #(and (not= f (io/file %)) (util/guard (io/as-relative-path %)) %))))

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
