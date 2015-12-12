(ns boot.core
  "The boot core API."
  (:require
    [clojure.java.io              :as io]
    [clojure.set                  :as set]
    [clojure.walk                 :as walk]
    [clojure.repl                 :as repl]
    [clojure.string               :as string]
    [boot.pod                     :as pod]
    [boot.git                     :as git]
    [boot.cli                     :as cli2]
    [boot.file                    :as file]
    [boot.tmpregistry             :as tmp]
    [boot.tmpdir                  :as tmpd]
    [boot.util                    :as util]
    [boot.from.io.aviso.exception :as ex]
    [boot.from.clojure.tools.cli  :as cli])
  (:import
    [boot App]
    [java.io File]
    [java.net URLClassLoader URL]
    [java.lang.management ManagementFactory]
    [java.util.concurrent LinkedBlockingQueue TimeUnit Semaphore]))

(declare watch-dirs sync! post-env! get-env set-env! tmp-file tmp-dir ls)

(declare ^{:dynamic true :doc "The running version of boot app."}        *app-version*)
(declare ^{:dynamic true :doc "The script's name (when run as script)."} *boot-script*)
(declare ^{:dynamic true :doc "The running version of boot core."}       *boot-version*)
(declare ^{:dynamic true :doc "Command line options for boot itself."}   *boot-opts*)
(declare ^{:dynamic true :doc "Count of warnings during build."}         *warnings*)

(def new-build-at     "Latest build occured at time."         (atom 0))
(def last-file-change "Last source file watcher update time." (atom 0))

;; Internal helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private cli-base          (atom {}))
(def ^:private tmpregistry       (atom nil))
(def ^:private cleanup-fns       (atom []))
(def ^:private boot-env          (atom nil))
(def ^:private tempdirs          (atom #{}))
(def ^:private tempdirs-lock     (Semaphore. 1 true))
(def ^:private src-watcher       (atom (constantly nil)))
(def ^:private repo-config-fn    (atom identity))
(def ^:private default-repos     [["clojars"       "https://clojars.org/repo/"]
                                  ["maven-central" "https://repo1.maven.org/maven2"]])

(def ^:private masks
  {:user     {:user true}
   :input    {:input true}
   :output   {:output true}
   :cache    {:input nil :output nil}
   :asset    {:input nil :output true}
   :source   {:input true :output nil}
   :resource {:input true :output true}})

(defn- get-dirs [this masks+]
  (let [dirs        (:dirs this)
        has-mask?   #(= %1 (select-keys %2 (keys %1)))
        filter-keys #(->> %1 (filter (partial has-mask? %2)))]
    (->> masks+ (map masks) (apply merge) (filter-keys dirs) (map tmp-file) set)))

(defn- get-add-dir [this masks+]
  (let [user?  (contains? masks+ :user)
        u-dirs (when-not user? (get-dirs this #{:user}))]
    (-> this (get-dirs masks+) (set/difference u-dirs) first)))

(defn- get-files [this masks+]
  (let [dirs (get-dirs this masks+)]
    (->> this ls (filter (comp dirs tmp-dir)) set)))

(defn- tmp-dir*
  [key]
  (tmp/mkdir! @tmpregistry key))

(def new-fileset
  (memoize
    (fn []
      (boot.tmpdir.TmpFileSet. @tempdirs {} (tmp-dir* ::blob)))))

(defn- tmp-dir**
  [key & masks+]
  (let [k (or key (keyword "boot.core" (str (gensym))))
        m (->> masks+ (map masks) (apply merge))
        in-fileset? (or (:input m) (:output m))]
    (util/with-let [d (tmp-dir* k)]
      (when in-fileset?
        (let [t (tmpd/map->TmpDir (assoc m :dir d))]
          (swap! tempdirs conj t)
          (when (:input t)
            (set-env! :directories #(conj % (.getPath (:dir t))))))))))

(defn- add-user-asset    [fileset dir] (tmpd/add fileset (get-add-dir fileset #{:user :asset}) dir {}))
(defn- add-user-source   [fileset dir] (tmpd/add fileset (get-add-dir fileset #{:user :source}) dir {}))
(defn- add-user-resource [fileset dir] (tmpd/add fileset (get-add-dir fileset #{:user :resource}) dir {}))

(defn- user-temp-dirs     [] (get-dirs {:dirs @tempdirs} #{:user}))
(defn- user-asset-dirs    [] (get-dirs {:dirs @tempdirs} #{:user :asset}))
(defn- user-source-dirs   [] (get-dirs {:dirs @tempdirs} #{:user :source}))
(defn- user-resource-dirs [] (get-dirs {:dirs @tempdirs} #{:user :resource}))
(defn- non-user-temp-dirs [] (->> [:asset :source :resource]
                                  (map #(get-dirs {:dirs @tempdirs} #{%}))
                                  (apply set/union)
                                  (#(set/difference % (user-temp-dirs)))))

(defn- sync-user-dirs!
  []
  (doseq [[k d] {:source-paths   (user-source-dirs)
                 :resource-paths (user-resource-dirs)
                 :asset-paths    (user-asset-dirs)}]
    (when-let [s (->> (get-env k)
                      (filter #(.isDirectory (io/file %)))
                      seq)]
      (util/dbug "Syncing project dirs to temp dirs...\n")
      (binding [file/*hard-link* false]
        (util/with-semaphore tempdirs-lock
          (apply file/sync! :time (first d) s))))))

(defn- set-fake-class-path!
  "Sets the fake.class.path system property to reflect all JAR files on the
  pod class path plus the :source-paths and :resource-paths. Note that these
  directories are not actually on the class path (this is why it's the fake
  class path). This property is a workaround for IDEs and tools that expect
  the full class path to be determined by the java.class.path property.

  Also sets the boot.class.path system property which is the same as above
  except that the actual class path directories are used instead of the user's
  project directories. This property can be used to configure Java tools that
  would otherwise be looking at java.class.path expecting it to have the full
  class path (the javac task uses it, for example, to configure the Java com-
  piler class)."
  []
  (let [user-dirs  (->> (get-env)
                        ((juxt :source-paths :resource-paths))
                        (apply concat)
                        (map #(.getAbsolutePath (io/file %))))
        paths      (->> (pod/get-classpath) (map #(.getPath (URL. %))))
        dir?       (comp (memfn isDirectory) io/file)
        fake-paths (->> paths (remove dir?) (concat user-dirs))
        separated  (partial string/join (System/getProperty "path.separator"))]
    (System/setProperty "boot.class.path" (separated paths))
    (System/setProperty "fake.class.path" (separated fake-paths))))

(defn- set-user-dirs!
  "Resets the file watchers that sync the project directories to their
  corresponding temp dirs, reflecting any changes to :source-paths, etc."
  []
  (@src-watcher)
  (let [debounce  (or (get-env :watcher-debounce) 10)
        env-keys  [:source-paths :resource-paths :asset-paths]
        dir-paths (set (->> (mapcat get-env env-keys)
                            (filter #(.isDirectory (io/file %)))))
        on-change (fn [_]
                    (sync-user-dirs!)
                    (reset! last-file-change (System/currentTimeMillis)))]
    (reset! src-watcher (watch-dirs on-change dir-paths :debounce debounce))
    (set-fake-class-path!)
    (sync-user-dirs!)))

(defn- do-cleanup!
  "Cleanup handler. This is run after the build process is complete. Tasks can
  register cleanup callbacks via the cleanup macro below."
  []
  (doseq [f @cleanup-fns] (util/guard (f)))
  (reset! cleanup-fns []))

(defn- printable-readable?
  "If the form can be round-tripped through pr-str -> read-string -> pr-str
  then form is returned, otherwise nil."
  [form]
  (or (nil? form)
      (false? form)
      (try (read-string (pr-str form)) (catch Throwable _))))

(defn load-data-readers!
  "Refresh *data-readers* with readers from newly acquired dependencies."
  []
  (#'clojure.core/load-data-readers)
  (set! *data-readers* (.getRawRoot #'*data-readers*)))

(defn- find-version-conflicts
  "compute a seq of [name new-coord old-coord] elements describing version conflicts
   when resolving the 'old' dependency vector and the 'new' dependency vector"
  [old new env]
  (let [resolve-deps (fn [deps] (->> deps
                                     (assoc env :dependencies)
                                     pod/resolve-dependencies
                                     (map (juxt (comp first :dep) (comp second :dep)))
                                     (into {})))
        old-deps (resolve-deps old)
        new-deps (resolve-deps new)]
    (->> new-deps
         (map (fn [[name coord]] [name coord (get old-deps name coord)]))
         (remove (fn [[name new-coord old-coord]] (= new-coord old-coord))))))

(defn- report-version-conflicts
  "Warn, when the version of a dependency changes. Call this with the
  result of find-version-conflicts as arguments"
  [coll]
  (doseq [[name new-coord old-coord] coll]
    (util/warn "Warning: version conflict detected: %s version changes from %s to %s\n" name old-coord new-coord)))

(defn- add-dependencies!
  "Add Maven dependencies to the classpath, fetching them if necessary."
  [old new env]
  (assert (vector? new) "env :dependencies must be a vector")
  (let [new (pod/apply-global-exclusions (:exclusions env) new)]
    (report-version-conflicts (find-version-conflicts old new env))
    (->> new (assoc env :dependencies) pod/add-dependencies)
    (set-fake-class-path!)
    new))

(defn- add-directories!
  "Add URLs (directories or jar files) to the classpath."
  [dirs]
  (set-fake-class-path!)
  (doseq [dir dirs] (pod/add-classpath dir)))

(defn- configure!*
  "Performs side-effects associated with changes to the env atom. Boot adds this
  function as a watcher on it."
  [old new]
  (alter-var-root #'boot.pod/env (constantly new))
  (doseq [k (set/union (set (keys old)) (set (keys new)))]
    (let [o (get old k ::noval)
          n (get new k ::noval)]
      (when (not= o n) (post-env! k o n new)))))

(defn- add-wagon!
  "Adds a maven wagon dependency to the worker pod and initializes it with an
  optional map of scheme handlers."
  ([maven-coord scheme-map]
   (add-wagon! nil [maven-coord] (get-env) scheme-map))
  ([old new env]
   (assert (vector? new) "env :wagons must be a vector")
   (add-wagon! old new env nil))
  ([old new env scheme-map]
   (doseq [maven-coord new]
     (pod/with-call-worker
       (boot.aether/add-wagon ~env ~maven-coord ~scheme-map)))
   new))

(defn- order-set-env-keys
  "Ensures that :dependencies are processed last, because changes to other
  keys affect dependency resolution."
  [kvs]
  (let [dk :dependencies]
    (->> kvs (sort-by first #(cond (= %1 dk) 1 (= %2 dk) -1 :else 0)))))

(defn- parse-task-opts
  "Given and argv and a tools.cli type argspec spec, returns a vector of the
  parsed option map and a list of remaining non-option arguments. This is how
  tasks in a pipeline created on the cli are separated into individual tasks
  and task options."
  [argv spec]
  (loop [opts [] [car & cdr :as argv] argv]
    (if-not car
      [opts argv]
      (let [opts* (conj opts car)
            parsd (cli/parse-opts opts* spec :in-order true)]
        (if (seq (:arguments parsd)) [opts argv] (recur opts* cdr))))))

(defmacro ^:private daemon
  "Evaluate the body in a new daemon thread."
  [& body]
  `(doto (Thread. (fn [] ~@body)) (.setDaemon true) .start))

;; Tempdir and Fileset API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ^:private deprecate! [was is]
  `(let [msg# (format "%s was deprecated, please use %s instead\n" '~was '~is)
         warn# (delay (util/warn msg#))]
     (defn ^:deprecated ~was [& args#]
       @warn#
       (util/dbug (ex/format-exception (Exception. msg#)))
       (apply ~is args#))))

(defn tmp-dir!
  "Creates a boot-managed temporary directory, returning a java.io.File."
  []
  (tmp-dir** nil :cache))
(deprecate! temp-dir! tmp-dir!)

(defn cache-dir!
  "Returns a directory which is managed by boot but whose contents will not be
  deleted after the build is complete. The :global option specifies that the
  directory is shared by all projects. The default behavior returns different
  directories for the same key when run in different projects."
  [key & {:keys [global]}]
  (assert (and (keyword? key) (namespace key))
          "cache key must be a namespaced keyword")
  (->> (io/file ".")
       .getCanonicalFile
       file/split-path
       (when-not global)
       (into [(App/getBootDir) "cache" "cache" (if global "global" "project")])
       (#(into % ((juxt namespace name) key)))
       (apply io/file)
       (#(doto % .mkdirs))))

;; TmpFile API

(defn tmp-path
  "Returns the tmpfile's path relative to the fileset root."
  [tmpfile]
  (tmpd/path tmpfile))
(deprecate! tmppath tmp-path)

(defn tmp-dir
  "Returns the temporary directory containing the tmpfile."
  [tmpfile]
  (tmpd/dir tmpfile))
(deprecate! tmpdir tmp-dir)

(defn tmp-file
  "Returns the java.io.File object for the tmpfile."
  [tmpfile]
  (tmpd/file tmpfile))
(deprecate! tmpfile tmp-file)

(defn tmp-time
  "Returns the last modified timestamp for the tmpfile."
  [tmpfile]
  (tmpd/time tmpfile))
(deprecate! tmptime tmp-time)

;; TmpFileSet API

(defn tmp-get
  "Given a fileset and a path, returns the associated TmpFile record. If the
  not-found argument is specified and the TmpFile is not in the fileset then
  not-found is returned, otherwise nil."
  [fileset path & [not-found]]
  (get-in fileset [:tree path] not-found))
(deprecate! tmpget tmp-get)

(defn user-dirs
  "Get a list of directories containing files that originated in the project's
  source, resource, or asset paths."
  [fileset]
  (get-dirs fileset #{:user}))

(defn input-dirs
  "Get a list of directories containing files with input roles."
  [fileset]
  (get-dirs fileset #{:input}))

(defn output-dirs
  [fileset]
  (get-dirs fileset #{:output}))

(defn user-files
  "Get a set of TmpFile objects corresponding to files that originated in
  the project's source, resource, or asset paths."
  [fileset]
  (get-files fileset #{:user}))

(defn input-files
  "Get a set of TmpFile objects corresponding to files with input role."
  [fileset]
  (get-files fileset #{:input}))

(defn input-fileset
  "FIXME: document"
  [fileset]
  (tmpd/restrict-dirs fileset (input-dirs fileset)))

(defn output-files
  "Get a set of TmpFile objects corresponding to files with output role."
  [fileset]
  (get-files fileset #{:output}))

(defn output-fileset
  "FIXME: document"
  [fileset]
  (tmpd/restrict-dirs fileset (output-dirs fileset)))

(defn ls
  "Get a set of TmpFile objects for all files in the fileset."
  [fileset]
  (tmpd/ls fileset))

(defn commit!
  "Make the underlying temp directories correspond to the immutable fileset
  tree structure."
  [fileset]
  (util/with-semaphore tempdirs-lock
    (tmpd/commit! fileset)))

(defn rm
  "Removes files from the fileset tree, returning a new fileset object. This
  does not affect the underlying filesystem in any way."
  [fileset files]
  (tmpd/rm fileset files))

(defn- non-user-dir-for
  "Given a fileset and a directory d in that fileset's set of underlying temp
  directories, returns a directory of the same type (ie. source, resource, or
  asset) but not a user dir (ie. not one of the directories that is synced to
  project dirs)."
  [fileset d]
  (let [u (user-dirs fileset)]
    (or (and (not (u d)) d)
        (->> (cond ((get-dirs fileset #{:asset}) d) #{:asset}
                   ((get-dirs fileset #{:source}) d) #{:source}
                   ((get-dirs fileset #{:resource}) d) #{:resource})
             (get-add-dir fileset)))))

(defn mv
  "Given a fileset and two paths in the fileset, from-path and to-path, moves
  the tmpfile at from-path to to-path, returning a new fileset."
  [fileset ^String from-path ^String to-path]
  (tmpd/mv fileset from-path to-path))

(defn cp
  "Given a fileset and a dest-tmpfile from that fileset, overwrites the dest
  tmpfile with the contents of the java.io.File src-file."
  [fileset ^File src-file dest-tmpfile]
  (->> (tmp-dir dest-tmpfile)
       (non-user-dir-for fileset)
       (assoc dest-tmpfile :dir)
       (tmpd/cp fileset src-file)))

(defn add-asset
  "Add the contents of the java.io.File dir to the fileset's assets."
  [fileset ^File dir & {:keys [mergers include exclude] :as opts}]
  (tmpd/add fileset (get-add-dir fileset #{:asset}) dir opts))

(defn add-cached-asset
  "FIXME: document"
  [fileset cache-key cache-fn & {:keys [mergers include exclude] :as opts}]
  (tmpd/add-cached fileset (get-add-dir fileset #{:asset}) cache-key cache-fn opts))

(defn mv-asset
  "FIXME: document"
  [fileset tmpfiles]
  (tmpd/add-tmp fileset (get-add-dir fileset #{:asset}) tmpfiles))

(defn add-source
  "Add the contents of the java.io.File dir to the fileset's sources."
  [fileset ^File dir & {:keys [mergers include exclude] :as opts}]
  (tmpd/add fileset (get-add-dir fileset #{:source}) dir opts))

(defn add-cached-source
  "FIXME: document"
  [fileset cache-key cache-fn & {:keys [mergers include exclude] :as opts}]
  (tmpd/add-cached fileset (get-add-dir fileset #{:source}) cache-key cache-fn opts))

(defn mv-source
  "FIXME: document"
  [fileset tmpfiles]
  (tmpd/add-tmp fileset (get-add-dir fileset #{:source}) tmpfiles))

(defn add-resource
  "Add the contents of the java.io.File dir to the fileset's resources."
  [fileset ^File dir & {:keys [mergers include exclude] :as opts}]
  (tmpd/add fileset (get-add-dir fileset #{:resource}) dir opts))

(defn add-cached-resource
  "FIXME: document"
  [fileset cache-key cache-fn & {:keys [mergers include exclude] :as opts}]
  (tmpd/add-cached fileset (get-add-dir fileset #{:resource}) cache-key cache-fn opts))

(defn mv-resource
  "FIXME: document"
  [fileset tmpfiles]
  (tmpd/add-tmp fileset (get-add-dir fileset #{:resource}) tmpfiles))

(defn add-meta
  "Adds metadata about the files in the filesystem to their corresponding
  TmpFile objects in the fileset. The meta-map is expected to be a map with
  string paths as keys and maps of metadata as values. These metadata maps
  will be merged into the TmpFile objects associated with the paths."
  [fileset meta-map]
  (-> #(let [k [:tree %2]
             x [:dir :path :id :time]]
         (if-not (get-in %1 k)
           %1
           (update-in %1 k merge (apply dissoc %3 x))))
      (reduce-kv fileset meta-map)))

(defn fileset-diff
  "Returns a new fileset containing files that were added or modified. Removed
  files are not considered. The optional props arguments can be any of :time,
  :hash, or both, specifying whether to consider changes to last modified time
  or content md5 hash of the files (the default is both)."
  [before after & props]
  (apply tmpd/diff before after props))

(defn fileset-added
  "Returns a new fileset containing only files that were added."
  [before after & props]
  (apply tmpd/added before after props))

(defn fileset-removed
  "Returns a new fileset containing only files that were removed."
  [before after & props]
  (apply tmpd/removed before after props))

(defn fileset-changed
  "Returns a new fileset containing only files that were changed."
  [before after & props]
  (apply tmpd/changed before after props))

(defn fileset-namespaces
  "Returns a set of symbols: the namespaces defined in this fileset."
  [fileset]
  (let [dirs (->> fileset input-dirs (map (memfn getPath)))]
    (set (pod/with-call-worker (boot.namespace/find-namespaces-in-dirs [~@dirs])))))

;; Tempdir helpers

(defn empty-dir!
  "For each directory in dirs, recursively deletes all files and subdirectories.
  The directories in dirs themselves are not deleted."
  [& dirs]
  (apply file/empty-dir! dirs))

(defn sync!
  "Given a dest directory and one or more srcs directories, overlays srcs on
  dest, removing files in dest that are not in srcs. Uses file modification
  timestamps to decide which version of files to emit to dest. Uses hardlinks
  instead of copying file contents. File modification times are preserved."
  [dest & srcs]
  (apply file/sync! :time dest srcs))

;; Boot Environment ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
    (do (pod/require-in pod/worker-pod "boot.watcher")
        (let [q       (LinkedBlockingQueue.)
              watcher (apply file/watcher! :time dirs)
              paths   (into-array String dirs)
              k       (.invoke pod/worker-pod "boot.watcher/make-watcher" q paths)]
          (daemon
            (loop [ret (util/guard [(.take q)])]
              (when ret
                (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
                  (recur (conj ret more))
                  (let [changed (watcher)]
                    (when-not (empty? changed) (callback changed))
                    (recur (util/guard [(.take q)])))))))
          #(.invoke pod/worker-pod "boot.watcher/stop-watcher" k)))))

(defn rebuild!
  "Manually trigger build watch."
  []
  (reset! new-build-at (System/currentTimeMillis)))

(defn init!
  "Initialize the boot environment. This is normally run once by boot at
  startup. There should be no need to call this function directly."
  []
  (->> (io/file ".")
       .getCanonicalFile
       file/split-path
       rest
       (apply io/file (App/getBootDir) "cache" "tmp")
       tmp/registry
       tmp/init!
       (reset! tmpregistry))
  (doto boot-env
    (reset! {:watcher-debounce 10
             :dependencies     []
             :directories      #{}
             :source-paths     #{}
             :resource-paths   #{}
             :asset-paths      #{}
             :target-path      "target"
             :repositories     default-repos})
    (add-watch ::boot #(configure!* %3 %4)))
  (set-fake-class-path!)
  (tmp-dir** nil :asset)
  (tmp-dir** nil :source)
  (tmp-dir** nil :resource)
  (tmp-dir** nil :user :asset)
  (tmp-dir** nil :user :source)
  (tmp-dir** nil :user :resource)
  (pod/add-shutdown-hook! do-cleanup!))

(defmulti post-env!
  "Event handler called when the env atom is modified. This handler is for
  performing side-effects associated with maintaining the application state in
  the env atom. For example, when `:src-paths` is modified the handler adds
  the new directories to the classpath."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod post-env! ::default         [key old new env] nil)
(defmethod post-env! :directories      [key old new env] (add-directories! new))
(defmethod post-env! :source-paths     [key old new env] (set-user-dirs!))
(defmethod post-env! :resource-paths   [key old new env] (set-user-dirs!))
(defmethod post-env! :asset-paths      [key old new env] (set-user-dirs!))
(defmethod post-env! :watcher-debounce [key old new env] (set-user-dirs!))

(defmulti pre-env!
  "This multimethod is used to modify how new values are merged into the boot
  atom when `set-env!` is called. This function's result will become the new
  value associated with the given `key` in the env atom."
  (fn [key old-value new-value env] key) :default ::default)

(defn- merge-or-replace [x y]   (if-not (coll? x) y (into x y)))
(defn- merge-if-coll    [x y]   (if-not (coll? x) x (into x y)))
(defn- assert-set       [k new] (assert (set? new) (format "env %s must be a set" k)))
(defn- canonical-repo   [repo]  (if (map? repo) repo {:url repo}))

(defmethod pre-env! ::default       [key old new env] new)
(defmethod pre-env! :directories    [key old new env] (set/union old new))
(defmethod pre-env! :source-paths   [key old new env] (assert-set key new) new)
(defmethod pre-env! :resource-paths [key old new env] (assert-set key new) new)
(defmethod pre-env! :asset-paths    [key old new env] (assert-set key new) new)
(defmethod pre-env! :wagons         [key old new env] (add-wagon! old new env))
(defmethod pre-env! :dependencies   [key old new env] (add-dependencies! old new env))
(defmethod pre-env! :repositories   [key old new env] (->> new (mapv (fn [[k v]] [k (@repo-config-fn (canonical-repo v))]))))

(add-watch repo-config-fn (gensym) (fn [& _] (set-env! :repositories identity)))

(defn configure-repositories!
  "Get or set the repository configuration callback function. The function
  will be applied to all repositories added to the boot env, it should return
  the repository map with any additional configuration (like credentials, for
  example)."
  ([ ] @repo-config-fn)
  ([f] (reset! repo-config-fn f)))

(defn get-env
  "Returns the value associated with the key `k` in the boot environment, or
  `not-found` if the environment doesn't contain key `k` and `not-found` was
  given. Calling this function with no arguments returns the environment map."
  [& [k not-found]]
  (if k (get @boot-env k not-found) @boot-env))

(defn set-env!
  "Update the boot environment atom `this` with the given key-value pairs given
  in `kvs`. See also `post-env!` and `pre-env!`. The values in the env map must
  be both printable by the Clojure printer and readable by its reader. If the
  value for a key is a function, that function will be applied to the current
  value of that key and the result will become the new value (similar to how
  clojure.core/update-in works."
  [& kvs]
  (doseq [[k v] (order-set-env-keys (partition 2 kvs))]
      (let [v'  (if-not (fn? v) v (v (get-env k)))
            v'' (if-let [b (get @cli-base k)] (merge-if-coll b v') v')]
        (assert (printable-readable? v'')
                (format "value not readable by Clojure reader\n%s => %s" (pr-str k) (pr-str v'')))
        (swap! boot-env update-in [k] (partial pre-env! k) v'' @boot-env))))

(defn merge-env!
  "Merges the new values into the current values for the given keys in the env
  map. Uses a merging strategy that is appropriate for the given key (eg. uses
  clojure.core/into for keys whose values are collections and simply replaces
  Keys whose values aren't collections)."
  [& kvs]
  (->> (partition 2 kvs)
       (mapcat (fn [[k v]] [k #(merge-or-replace % v)]))
       (apply set-env!)))

;; Defining Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro deftask
  "Define a boot task."
  [sym & forms]
  (let [[heads [bindings & tails]] (split-with (complement vector?) forms)]
    `(do
       (when-let [existing-deftask# (resolve '~sym)]
         (when (= *ns* (-> existing-deftask# meta :ns))
           (boot.util/warn
             "Warning: deftask %s/%s was overridden\n" *ns* '~sym)))
       (cli2/defclifn ~(vary-meta sym assoc ::task true)
         ~@heads
         ~bindings
         (let [provided# (->> ~'*opts* keys set)
               optspec#  (->> #'~sym meta :arglists first second)
               allowed#  (->> optspec# :keys (map (comp keyword str)) set)
               unknown#  (set/difference provided# allowed#)]
           (when (seq unknown#)
             (util/warn "%s: unknown option(s): %s\n" '~sym (string/join ", " unknown#))))
         ~@tails))))

;; Boot Lifecycle ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro cleanup
  "Evaluate body after tasks have been run. This macro is meant to be called
  from inside a task definition, and is provided as a means to shutdown or
  clean up persistent resources created by the task (eg. threads, files, etc.)"
  [& body]
  `(swap! @#'boot.core/cleanup-fns conj (fn [] ~@body)))

(defn reset-fileset
  "Updates the user directories in the fileset with the latest project files,
  returning a new immutable fileset. When called with no args returns a new
  fileset containing only the latest project files."
  [& [fileset]]
  (let [fileset (when fileset (rm fileset (user-files fileset)))]
    (sync-user-dirs!)
    (-> (new-fileset)
        (add-user-asset (first (user-asset-dirs)))
        (add-user-source (first (user-source-dirs)))
        (add-user-resource (first (user-resource-dirs)))
        (update-in [:tree] merge (:tree fileset)))))

(defn reset-build!
  "Resets mutable build state to default values. This includes such things as
  warning counters etc., state that is relevant to a single build cycle. This
  function should be called before each build iteration."
  []
  (reset! *warnings* 0))

(defn- construct-tasks
  "Given command line arguments (strings), constructs a task pipeline by
  resolving the task vars, calling the task constructors with the arguments
  for that task, and composing them to form the pipeline."
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

(defn- sync-target
  "Copy output files to the target directory (if BOOT_EMIT_TARGET is not 'no')."
  [before after]
  (when-not (= "no" (boot.App/config "BOOT_EMIT_TARGET"))
    (let [tgt  (get-env :target-path)
          diff (fileset-diff before after)]
      (when (seq (output-files diff))
        (binding [file/*hard-link* false]
          (apply file/sync! :time tgt (output-dirs after)))
        (file/delete-empty-subdirs! tgt)))))

(defn- run-tasks
  "Given a task pipeline, builds the initial fileset, sets the initial build
  state, and runs the pipeline."
  [task-stack]
  (binding [*warnings* (atom 0)]
    (let [fs (commit! (reset-fileset))]
      ((task-stack #(do (sync-target fs %) (sync-user-dirs!) %)) fs))))

(defn boot
  "The REPL equivalent to the command line 'boot'. If all arguments are
  strings then they are treated as if they were given on the command line.
  Otherwise they are assumed to evaluate to task middleware."
  [& argv]
  (try @(future ;; see issue #6
          (util/with-let [_ nil]
            (run-tasks
              (cond (every? fn? argv)     (apply comp argv)
                    (every? string? argv) (apply construct-tasks argv)
                    :else (throw (IllegalArgumentException.
                                   "Arguments must be either all strings or all fns"))))))
       (finally (do-cleanup!))))

;; Low-Level Tasks, Helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-pre-wrap
  "Given a binding and body expressions, constructs a task handler. The body
  expressions are evaluated with the current fileset bound to binding, and the
  result is passed to the next handler in the pipeline. The fileset obtained
  from the next handler is then returned up the handler stack. The body must
  evaluate to a fileset object. Roughly equivalent to:

      (fn [next-handler]
        (fn [binding]
          (next-handler (do ... ...))))

  where ... are the given body expressions."
  [bind & body]
  (let [bind (if (vector? bind) (first bind) bind)]
    `(fn [next-task#]
       (fn [fileset#]
         (assert (tmpd/tmpfileset? fileset#)
                 "argument to task handler not a fileset")
         (let [~bind fileset#
               result# (do ~@body)]
           (assert (tmpd/tmpfileset? result#)
                   "task handler must return a fileset")
           (next-task# result#))))))

(defmacro with-post-wrap
  [bind & body]
  "Given a binding and body expressions, constructs a task handler. The next
  handler is called with the current fileset, and the result is bound to
  binding. The body expressions are then evaluated for side effects and the
  bound fileset is returned up the handler stack. Roughly equivalent to:

      (fn [next-handler]
        (fn [fileset]
          (let [binding (next-handler fileset)]
            (do ... ...)
            binding)))

  where ... are the given body expressions."
  (let [bind (if (vector? bind) (first bind) bind)]
    `(fn [next-task#]
       (fn [fileset#]
         (assert (tmpd/tmpfileset? fileset#)
                 "argument to task handler not a fileset")
         (let [result# (next-task# fileset#)
               ~bind   result#]
           (assert (tmpd/tmpfileset? result#)
                   "task handler must return a fileset")
           ~@body
           result#)))))

(defmacro with-pass-thru
  "Given a binding and body expressions, constructs a task handler. The body
  expressions are evaluated for side effects with the current fileset bound
  to binding. The current fileset is then passed to the next handler and the
  result is then returned up the handler stack."
  [bind & body]
  (let [bind (if (vector? bind) (first bind) bind)]
    `(with-pre-wrap [fs#]
       (util/with-let [~bind fs#] ~@body))))

(defmacro fileset-reduce
  "Given a fileset, a function get-files that selects files from the fileset,
  and a number of reducing functions, composes the reductions. The result of
  the previous reduction and the result of get-files applied to it are passed
  through to the next reducing function."
  [fileset get-files & reducers]
  (if-not (seq reducers)
    fileset
    (let [each-reducer #(vector `((juxt identity ~get-files)) `(apply reduce ~%))]
      `(->> ~fileset ~@(mapcat each-reducer reducers)))))

;; Task Configuration Macros ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  "Given a number of task/map-of-curried-arguments pairs, replaces the root
  bindings of the tasks with their curried values. For example:

      (task-options!
        repl {:port     12345}
        jar  {:manifest {:howdy \"world\"}})

  You can update options, too, by providing a function instead of an option
  map. This function will be passed the current option map and its result will
  be used as the new one. For example:

      (task-options!
        repl #(update-in % [:port] (fnil inc 1234))
        jar  #(assoc-in % [:manifest \"ILike\"] \"Turtles\"))"
  [& task-option-pairs]
  `(do ~@(for [[task opts] (partition 2 task-option-pairs)]
           `(let [opt# ~opts
                  var# (var ~task)
                  old# (:task-options (meta var#))
                  new# (if (map? opt#) opt# (opt# old#))
                  arg# (mapcat identity new#)]
              (replace-task! [t# ~task] (fn [& xs#] (apply t# (concat arg# xs#))))
              (alter-meta! var# (fn [x#] (assoc x# :task-options new#)))))
       nil))

;; Task Utility Functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn json-generate
  "Same as cheshire.core/generate-string."
  [x & [opt-map]]
  (pod/with-call-worker
    (cheshire.core/generate-string ~x ~opt-map)))

(defn json-parse
  "Same as cheshire.core/parse-string."
  [x & [key-fn]]
  (pod/with-call-worker
    (cheshire.core/parse-string ~x ~key-fn)))

(defn yaml-generate
  "Same as clj-yaml.core/generate-string."
  [x]
  (pod/with-call-worker
    (clj-yaml.core/generate-string ~x)))

(defn yaml-parse
  "Same as clj-yaml.core/parse-string."
  [x]
  (pod/with-call-worker
    (clj-yaml.core/parse-string ~x)))

(defn touch
  "Same as the Unix touch(1) program."
  [f]
  (.setLastModified f (System/currentTimeMillis)))

(defn git-files
  "Returns a list of files roughly equivalent to what you'd get with the git
  command line `git ls-files`. The :untracked option includes untracked files."
  [& {:keys [untracked]}]
  (git/ls-files :untracked untracked))

(defn file-filter
  "A file filtering function factory. FIXME: more documenting here."
  [mkpred]
  (fn [criteria files & [negate?]]
    (let [tmp?   (partial satisfies? tmpd/ITmpFile)
          ->file #(if (tmp? %) (io/file (tmp-path %)) (io/file %))]
      ((if negate? remove filter)
       #(some identity ((apply juxt (map mkpred criteria)) (->file %))) files))))

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

(defn by-path
  "This function takes two arguments: `paths` and `files`, where `path` is
  a seq of path strings like `[\"a/b/c/foo.clj\" \"bar.xml\"]` and `files` is
  a seq of file objects. Returns a seq of the files in `files` which have file
  paths listed in `paths`."
  [paths files & [negate?]]
  ((file-filter #(fn [f] (= (.getPath f) %))) paths files negate?))

(defn not-by-path
  "This function is the same as `by-path` but negated."
  [paths files]
  (by-path paths files true))

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
  file objects. Returns a seq of the files in `files` whose paths match one of
  the regex patterns in `res`."
  [res files & [negate?]]
  ((file-filter #(fn [f] (re-find % (.getPath f)))) res files negate?))

(defn not-by-re
  "This function is the same as `by-re` but negated."
  [res files]
  (by-re res files true))

;; General utilities ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn launch-nrepl
  "Launches an nREPL server in a pod. See the repl task for options."
  [& {:keys [pod] :as opts}]
  (require 'boot.repl)
  (let [mw      @@(resolve 'boot.repl/*default-middleware*)
        pod-id  (gensym "boot-pod-repl")
        deps    @@(resolve 'boot.repl/*default-dependencies*)
        pod-ns? (complement #{"aether" "worker"})
        pod-ns  #(when % (if (pod-ns? %) 'pod 'boot.pod))
        opts    (-> (dissoc opts :pod)
                    (update-in [:init-ns] #(or % (pod-ns pod)))
                    (assoc :default-middleware mw :default-dependencies deps))]
    (if (or (not pod) (= pod "core"))
      (let [server (@(resolve 'boot.repl/launch-nrepl) opts)]
        #(.close server))
      (let [p (pod/get-pods pod true)]
        (pod/with-eval-in p
          (require 'boot.repl)
          (def ~pod-id (boot.repl/launch-nrepl '~opts)))
        #(pod/with-eval-in p (.close ~pod-id))))))
