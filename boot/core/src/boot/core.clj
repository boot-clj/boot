(ns boot.core
  "The boot core API."
  (:require
    [clojure.java.io              :as io]
    [clojure.set                  :as set]
    [clojure.walk                 :as walk]
    [clojure.repl                 :as repl]
    [clojure.string               :as string]
    [boot.filesystem              :as fs]
    [boot.gpg                     :as gpg]
    [boot.pod                     :as pod]
    [boot.git                     :as git]
    [boot.cli                     :as cli2]
    [boot.file                    :as file]
    [boot.tmpregistry             :as tmp]
    [boot.tmpdir                  :as tmpd]
    [boot.util                    :as util]
    [boot.from.io.aviso.exception :as ex]
    [boot.from.clojure.tools.cli  :as cli]
    [boot.from.backtick           :as bt])
  (:import
    [boot App]
    [java.io File]
    [java.nio.file Path Paths]
    [java.net URLClassLoader URL]
    [java.lang.management ManagementFactory]
    [java.util.concurrent LinkedBlockingQueue TimeUnit Semaphore ExecutionException]))

(declare watch-dirs post-env! get-env set-env! tmp-file tmp-dir ls empty-dir! patch!)

(declare ^{:dynamic true :doc "The running version of boot app."}         *app-version*)
(declare ^{:dynamic true :doc "The script's name (when run as script)."}  *boot-script*)
(declare ^{:dynamic true :doc "The running version of boot core."}        *boot-version*)
(declare ^{:dynamic true :doc "Command line options for boot itself."}    *boot-opts*)
(declare ^{:dynamic true :doc "Count of warnings during build."}          *warnings*)

(def new-build-at     "Latest build occured at time."                     (atom 0))
(def last-file-change "Last source file watcher update time."             (atom 0))
(def bootignore       "Set of regexes source file paths must not match."  (atom nil))

;; Internal helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private cli-base          (atom {}))
(def ^:private tmpregistry       (->> (io/file ".")
                                      .getCanonicalFile
                                      file/split-path
                                      rest
                                      (apply io/file (App/getBootDir) "cache" "tmp")
                                      tmp/registry
                                      tmp/init!
                                      delay))
(def ^:private cleanup-fns       (atom []))
(def ^:private boot-env          (atom nil))
(def ^:private tempdirs          (atom #{}))
(def ^:private tempdirs-lock     (Semaphore. 1 true))
(def ^:private sync-dirs-lock    (Semaphore. 1 true))
(def ^:private src-watcher       (atom (constantly nil)))
(def ^:private repo-config-fn    (atom identity))
(def ^:private loaded-checkouts  (atom {}))
(def ^:private checkout-dirs     (atom #{}))
(def ^:private default-repos     [["clojars"       {:url "https://repo.clojars.org/"}]
                                  ["maven-central" {:url "https://repo1.maven.org/maven2"}]])
(def ^:private default-mirrors   (delay (let [c (boot.App/config "BOOT_CLOJARS_MIRROR")
                                              m (boot.App/config "BOOT_MAVEN_CENTRAL_MIRROR")
                                              f #(when %1 {%2 {:name (str %2 " mirror") :url %1}})]
                                          (merge {} (f c "clojars") (f m "maven-central")))))

(def ^:private masks
  {:user     {:user true}
   :input    {:input true}
   :output   {:output true}
   :cache    {:input nil :output nil}
   :asset    {:input nil :output true}
   :source   {:input true :output nil}
   :resource {:input true :output true}
   :checkout {:input nil :output nil :user nil :checkout true}})

(defn- genkw [& [prefix-string :as args]]
  (->> [(ns-name *ns*) (apply gensym args)] (map str) (apply keyword)))

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
      (boot.tmpdir.TmpFileSet.
        @tempdirs {} (tmp-dir* ::blob) (tmp-dir* ::scratch)))))

(defn- tmp-dir**
  [key & masks+]
  (let [k (or key (keyword "boot.core" (str (gensym))))
        m (->> masks+ (map masks) (apply merge))
        in-fileset? (or (:input m) (:output m))]
    (util/with-let [d (tmp-dir* k)]
      (cond (:checkout m) (swap! checkout-dirs conj d)
            in-fileset?   (swap! tempdirs conj (tmpd/map->TmpDir (assoc m :dir d))))
      (when (or (:checkout m) (:input m))
        (set-env! :directories #(conj % (.getPath d)))))))

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
  (util/with-semaphore-noblock sync-dirs-lock
    (let [debug-mesg (delay (util/dbug* "Syncing project dirs to temp dirs...\n"))]
      (doseq [[k d] {:asset-paths    (user-asset-dirs)
                     :source-paths   (user-source-dirs)
                     :resource-paths (user-resource-dirs)
                     :checkout-paths @checkout-dirs}]
        @debug-mesg
        (patch! (first d) (get-env k) :ignore @bootignore))
      (util/dbug* "Sync complete.\n"))))

(defn- set-fake-class-path!
  "Sets the :fake-class-path environment property to reflect all JAR files on
  the pod class path plus the :source-paths and :resource-paths. Note that
  these directories are not actually on the class path (this is why it's the
  fake class path). This property is a workaround for IDEs and tools that
  expect the full class path to be determined by the java.class.path property.

  Also sets the :boot-class-path environment property which is the same as above
  except that the actual class path directories are used instead of the user's
  project directories. This property can be used to configure Java tools that
  would otherwise be looking at java.class.path expecting it to have the full
  class path (the javac task uses it, for example, to configure the Java com-
  piler class).

  Also sets system properties fake.class.path and boot.class.path which mirror
  their environment counterparts, but are updated jvm-wide when changed. They
  are not reliable within a pod environment for this reason."

  []
  (.start
    (Thread.
      (bound-fn []
        (let [user-dirs       (->> (get-env)
                                   ((juxt :source-paths :resource-paths))
                                   (apply concat)
                                   (map #(.getAbsolutePath (io/file %))))
              paths           (->> (pod/get-classpath)
                                   (map #(.getPath (.toFile (Paths/get (.toURI (URL. %)))))))
              dir?            (comp (memfn isDirectory) io/file)
              fake-paths      (->> paths (remove dir?) (concat user-dirs))
              separated       (partial string/join (System/getProperty "path.separator"))
              boot-class-path (separated paths)
              fake-class-path (separated fake-paths)]

          (when (or (not= boot-class-path (get-env :boot-class-path))
                    (not= fake-class-path (get-env :fake-class-path)))
            (set-env! :fake-class-path fake-class-path
                      :boot-class-path boot-class-path))
          ;; Kept for backwards compatibility
          (System/setProperty "boot.class.path" boot-class-path)
          (System/setProperty "fake.class.path" fake-class-path))))))

(defn- set-user-dirs!
  "Resets the file watchers that sync the project directories to their
  corresponding temp dirs, reflecting any changes to :source-paths, etc."
  []
  (@src-watcher)
  (let [debounce  (or (get-env :watcher-debounce) 10)
        env-keys  [:source-paths :resource-paths :asset-paths :checkout-paths]
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

(defn- map-of-deps
  "build a map of dependency sym to version, including transitive deps."
  [env deps]
  (->> (assoc env :dependencies deps)
       pod/resolve-dependencies
       (map (juxt (comp first :dep) (comp second :dep)))
       (into {})))

(defn- find-version-conflicts
  "compute a seq of [name new-coord old-coord] elements describing version conflicts
  when resolving the 'old' dependency vector and the 'new' dependency vector"
  [old new env]
  (let [clj-name (symbol (boot.App/getClojureName))
        old-deps (-> (map-of-deps env old)
                     (assoc clj-name (clojure-version)))]
    (->> (map-of-deps env new) (keep (fn [[name coord]]
                                       (let [c (old-deps name coord)]
                                         (when (not= coord c) [name coord c])))))))

(defn- report-version-conflicts
  "Warn, when the version of a dependency changes. Call this with the
  result of find-version-conflicts as arguments"
  [coll]
  (let [clj-name (symbol (boot.App/getClojureName))]
    (doseq [[name new-coord old-coord] coll]
      (let [op (if (= name clj-name) "NOT" "ALSO")]
        (-> "Classpath conflict: %s version %s already loaded, %s loading version %s\n"
            (util/warn name old-coord op new-coord))))))

(defn- add-directories!
  "Add URLs (directories or jar files) to the classpath."
  [dirs]
  (set-fake-class-path!)
  (doseq [dir dirs] (pod/add-classpath dir)))

(defn- add-checkout-dependencies!
  "Add checkout dependencies that have not already been added."
  [{:keys [checkouts dependencies] :as env}]
  (let [loaded-syms   (->> @loaded-checkouts keys set)
        new-checkouts (->> checkouts (remove (comp loaded-syms first)))]
    (loop [dirs [] [[p :as dep] & deps] new-checkouts]
      (if-not p
        (when (seq dirs) (add-directories! dirs))
        (let [env       (dissoc env :checkouts)
              tmp       (tmp-dir* (genkw "checkout-tmp"))
              tmp-state (atom nil)
              jar-path  (pod/resolve-dependency-jar env dep)
              jar-dir   (.getParent (io/file jar-path))
              debounce  (or (:watcher-debounce env) 10)
              on-change (fn [_]
                          (util/dbug* "Refreshing checkout dependency %s...\n" (str p))
                          (util/with-semaphore tempdirs-lock
                            (with-open [jarfs (fs/mkjarfs (io/file jar-path))]
                              (->> (patch! tmp [(fs/->path jarfs)] :state @tmp-state)
                                   (reset! tmp-state)))))]
          (util/info "Adding checkout dependency %s...\n" (str p))
          (set-env! :checkout-paths #(conj % (.getPath tmp)))
          (watch-dirs on-change [jar-dir] :debounce debounce)
          (swap! loaded-checkouts assoc p {:dir tmp :jar (io/file jar-path)})
          (recur (conj dirs (.getPath tmp)) deps))))))

(defn- add-dependencies!
  "Add Maven dependencies to the classpath, fetching them if necessary."
  [old new {:keys [exclusions checkouts] :as env}]
  (assert (vector? new) "env :dependencies must be a vector")
  (let [versions (reduce #(apply assoc %1 (take 2 %2)) {} checkouts)
        dep-syms (set (map first new))
        chk-syms (set (map first checkouts))
        missing  (set/difference chk-syms dep-syms)
        new      (->> (pod/apply-global-exclusions exclusions new)
                      (mapv (fn [[p v :as d]] (assoc d 1 (versions p v))))
                      (assoc env :dependencies)
                      (pod/resolve-release-versions)
                      :dependencies)]
    (when (seq missing)
      (util/warn "Checkout deps missing from :dependencies in env: %s\n" (string/join ", " missing)))
    (report-version-conflicts (find-version-conflicts old new env))
    (->> new (assoc env :dependencies) pod/add-dependencies)
    (add-checkout-dependencies! env)
    (set-fake-class-path!)
    new))

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
     (let [{:keys [schemes] :as coord-map} (pod/coord->map maven-coord)
           maven-coord (pod/map->coord (dissoc coord-map :schemes))]
       (pod/with-call-worker
         (boot.aether/add-wagon ~env ~maven-coord ~(or scheme-map schemes)))))
   new))

(defn- order-set-env-keys
  "Ensures that :dependencies are processed last, because changes to other
  keys affect dependency resolution."
  [kvs]
  (let [dk :dependencies]
    (->> kvs (sort-by first #(cond (= %1 dk) 1 (= %2 dk) -1 :else 0)))))

(defmacro ^:private daemon
  "Evaluate the body in a new daemon thread."
  [& body]
  `(doto (Thread. (fn [] ~@body)) (.setDaemon true) .start))

;; Tempdir and Fileset API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ^:private deprecate! [version was is]
  (let [msg (format "%%s was deprecated, please use %s instead\n" (resolve is))]
    `(let [msg#  (delay (format ~msg (resolve '~was)))
           warn# (delay (util/warn-deprecated @msg#))]
       (do (defn ~(with-meta was {:deprecated version}) [& args#]
             @warn#
             (util/dbug* (ex/format-exception (Exception. @msg#)))
             (apply ~is args#))
           (alter-meta! #'~was assoc :doc @msg#)))))

(defn tmp-dir!
  "Creates a boot-managed temporary directory, returning a java.io.File."
  []
  (tmp-dir** nil :cache))
(deprecate! "2.0.0" temp-dir! tmp-dir!)

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
(deprecate! "2.0.0" tmppath tmp-path)

(defn tmp-dir
  "Returns the temporary directory containing the tmpfile."
  [tmpfile]
  (tmpd/dir tmpfile))
(deprecate! "2.0.0" tmpdir tmp-dir)

(defn tmp-file
  "Returns the java.io.File object for the tmpfile."
  [tmpfile]
  (tmpd/file tmpfile))
(deprecate! "2.0.0" tmpfile tmp-file)

(defn tmp-time
  "Returns the last modified timestamp for the tmpfile."
  [tmpfile]
  (tmpd/time tmpfile))
(deprecate! "2.0.0" tmptime tmp-time)

;; TmpFileSet API

(defn tmp-get
  "Given a fileset and a path, returns the associated TmpFile record. If the
  not-found argument is specified and the TmpFile is not in the fileset then
  not-found is returned, otherwise nil."
  [fileset path & [not-found]]
  (get-in fileset [:tree path] not-found))
(deprecate! "2.0.0" tmpget tmp-get)

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
  "Add the contents of the java.io.File dir to the fileset's assets.

  Option :include and :exclude, a #{} of regex expressions, control
  which paths are added; a path is only added if it matches an :include
  regex and does not match any :exclude regexes.

  If the operation produces duplicate entries, they will be merged using
  the rules specified by the :mergers option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

  You will typically use default mergers as in:

    [[ #\"data_readers.clj$\"    into-merger       ]
     [ #\"META-INF/services/.*\" concat-merger     ]
     [ #\".*\"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn
  from the first match is applied.

  The :meta option can be used to provide a map of metadata which will be
  merged into each TmpFile added to the fileset."
  [fileset ^File dir & {:keys [mergers include exclude meta] :as opts}]
  (tmpd/add fileset (get-add-dir fileset #{:asset}) dir opts))

(defn add-cached-asset
  "Like add-asset, but takes a cache-key (string) and cache-fn instead of
  a directory. If the cache-key is not found in Boot's fileset cache then the
  cache-fn is invoked with a single argument -- a directory in which to write
  the files that Boot should add to the cache -- and the contents of this
  directory are then added to the cache. In either case the cached files are
  then added to the fileset.

  The opts options are the same as those documented for boot.core/add-asset."
  [fileset cache-key cache-fn & {:keys [mergers include exclude meta] :as opts}]
  (tmpd/add-cached fileset (get-add-dir fileset #{:asset}) cache-key cache-fn opts))

(defn mv-asset
  "Given a collection of tmpfiles, moves them in the fileset such that they
  become asset files."
  [fileset tmpfiles]
  (tmpd/add-tmp fileset (get-add-dir fileset #{:asset}) tmpfiles))

(defn add-source
  "Add the contents of the java.io.File dir to the fileset's sources.

  Option :include and :exclude, a #{} of regex expressions, control
  which paths are added; a path is only added if it matches an :include
  regex and does not match any :exclude regexes.

  If the operation produces duplicate entries, they will be merged using
  the rules specified by the :mergers option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

  You will typically use default mergers as in:

    [[ #\"data_readers.clj$\"    into-merger       ]
     [ #\"META-INF/services/.*\" concat-merger     ]
     [ #\".*\"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn
  from the first match is applied.

  The :meta option can be used to provide a map of metadata which will be
  merged into each TmpFile added to the fileset."
  [fileset ^File dir & {:keys [mergers include exclude meta] :as opts}]
  (tmpd/add fileset (get-add-dir fileset #{:source}) dir opts))

(defn add-cached-source
  "Like add-source, but takes a cache-key (string) and cache-fn instead of
  a directory. If the cache-key is not found in Boot's fileset cache then the
  cache-fn is invoked with a single argument -- a directory in which to write
  the files that Boot should add to the cache -- and the contents of this
  directory are then added to the cache. In either case the cached files are
  then added to the fileset.

  The opts options are the same as those documented for boot.core/add-source."
  [fileset cache-key cache-fn & {:keys [mergers include exclude meta] :as opts}]
  (tmpd/add-cached fileset (get-add-dir fileset #{:source}) cache-key cache-fn opts))

(defn mv-source
  "Given a collection of tmpfiles, moves them in the fileset such that they
  become source files."
  [fileset tmpfiles]
  (tmpd/add-tmp fileset (get-add-dir fileset #{:source}) tmpfiles))

(defn add-resource
  "Add the contents of the java.io.File dir to the fileset's resources.

  Option :include and :exclude, a #{} of regex expressions, control
  which paths are added; a path is only added if it matches an :include
  regex and does not match any :exclude regexes.

  If the operation produces duplicate entries, they will be merged using
  the rules specified by the :mergers option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

  You will typically use default mergers as in:

    [[ #\"data_readers.clj$\"    into-merger       ]
     [ #\"META-INF/services/.*\" concat-merger     ]
     [ #\".*\"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn
  from the first match is applied.

  The :meta option can be used to provide a map of metadata which will be
  merged into each TmpFile added to the fileset."
  [fileset ^File dir & {:keys [mergers include exclude meta] :as opts}]
  (tmpd/add fileset (get-add-dir fileset #{:resource}) dir opts))

(defn add-cached-resource
  "Like add-resource, but takes a cache-key (string) and cache-fn instead of
  a directory. If the cache-key is not found in Boot's fileset cache then the
  cache-fn is invoked with a single argument -- a directory in which to write
  the files that Boot should add to the cache -- and the contents of this
  directory are then added to the cache. In either case the cached files are
  then added to the fileset.

  The opts options are the same as those documented for boot.core/add-resource."
  [fileset cache-key cache-fn & {:keys [mergers include exclude meta] :as opts}]
  (tmpd/add-cached fileset (get-add-dir fileset #{:resource}) cache-key cache-fn opts))

(defn mv-resource
  "Given a collection of tmpfiles, moves them in the fileset such that they
  become resource files."
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

(defn patch!
  "Given a dest and a sequence of srcs, all of which satisfying the IToPath
  protocol, updates dest such that it contains the union of the contents of
  srcs and returns an immutable value reflecting the final state of dest. The
  String, java.io.File, java.nio.file.Path, and java.nio.file.FileSystem types
  satisfy IToPath.

  Paths in dest that are not in any of the srcs will be removed; paths in any
  of the srcs that are not in dest or have different contents than the path
  in dest will be copied (or hardlinked, see :link option below).

  The :ignore option specifies a set of regex patterns for paths that will be
  ignored.

  The :state option specifies the initial state of dest (usually set to the
  value returned by a previous call to this function). When provided, this
  option makes the patching operation more efficient by eliminating the need
  to scan dest to establish its current state.

  The :link option specifies whether to create hardlinks instead of copying
  files from srcs to dest."
  [dest srcs & {:keys [ignore state link]}]
  (let [dest    (fs/->path dest)
        before  (or state (fs/mktree dest))
        merge'  #(->> (fs/mktree (fs/->path %2) :ignore ignore)
                      (fs/merge-trees %1))]
    (let [after (reduce merge' (fs/mktree) srcs)]
      (fs/patch! dest before after :link link))))

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
              k       (pod/with-invoke-worker (boot.watcher/make-watcher q paths))]
          (daemon
            (loop [ret (util/guard [(.take q)])]
              (when ret
                (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
                  (recur (conj ret more))
                  (let [changed (watcher)]
                    (when-not (empty? changed) (callback changed))
                    (recur (util/guard [(.take q)])))))))
          #(pod/with-invoke-worker (boot.watcher/stop-watcher k))))))

(defn rebuild!
  "Manually trigger build watch."
  []
  (reset! new-build-at (System/currentTimeMillis)))

(defn init!
  "Initialize the boot environment. This is normally run once by boot at
  startup. There should be no need to call this function directly."
  []
  (doto boot-env
    (reset! {:watcher-debounce 10
             :checkouts        []
             :dependencies     []
             :directories      #{}
             :source-paths     #{}
             :resource-paths   #{}
             :checkout-paths   #{}
             :asset-paths      #{}
             :exclusions       #{}
             :repositories     default-repos
             :mirrors          @default-mirrors})
    (add-watch ::boot #(configure!* %3 %4)))
  (set-fake-class-path!)
  (tmp-dir** nil :asset)
  (tmp-dir** nil :source)
  (tmp-dir** nil :resource)
  (tmp-dir** nil :user :asset)
  (tmp-dir** nil :user :source)
  (tmp-dir** nil :user :resource)
  (tmp-dir** nil :user :checkout)
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
(defmethod post-env! :checkout-paths   [key old new env] (set-user-dirs!))
(defmethod post-env! :asset-paths      [key old new env] (set-user-dirs!))
(defmethod post-env! :watcher-debounce [key old new env] (set-user-dirs!))

(defmulti pre-env!
  "This multimethod is used to modify how new values are merged into the boot
  atom when `set-env!` is called. This function's result will become the new
  value associated with the given `key` in the env atom."
  (fn [key old-value new-value env] key) :default ::default)

(defn- merge-or-replace [x y]   (if-not (coll? x) y (into x y)))
(defn- merge-if-coll    [x y]   (if-not (coll? x) x (into x y)))
(defn- assert-set       [k new] (assert (set? new) (format "env %s must be a set" k)) new)
(defn- canonical-repo   [repo]  (if (map? repo) repo {:url repo}))
(defn- canonical-deps   [deps]  (mapv pod/canonical-coord deps))

(defn- assert-disjoint
  [env key new]
  (util/with-let [_ new]
    (let [paths   (set (->> (assoc env key new)
                            ((juxt :source-paths :resource-paths :asset-paths))
                            (reduce into #{})
                            (keep #(some-> % io/file .getCanonicalFile))))
          parents (set (mapcat (comp rest file/parent-seq) paths))]
      (assert (empty? (set/intersection paths parents))
              "The :source-paths, :resource-paths, and :asset-paths must not overlap."))))

(defmethod pre-env! ::default       [key old new env] new)
(defmethod pre-env! :directories    [key old new env] (set/union old (assert-set key new)))
(defmethod pre-env! :source-paths   [key old new env] (assert-disjoint env key (assert-set key new)))
(defmethod pre-env! :resource-paths [key old new env] (assert-disjoint env key (assert-set key new)))
(defmethod pre-env! :asset-paths    [key old new env] (assert-disjoint env key (assert-set key new)))
(defmethod pre-env! :wagons         [key old new env] (add-wagon! old (canonical-deps new) env))
(defmethod pre-env! :checkouts      [key old new env] (canonical-deps new))
(defmethod pre-env! :dependencies   [key old new env] (add-dependencies! old (canonical-deps new) env))
(defmethod pre-env! :repositories   [key old new env] (->> new (mapv (fn [[k v]] [k (@repo-config-fn (canonical-repo v))]))))
(defmethod pre-env! :certificates   [key old new env] (pod/with-call-worker (boot.aether/load-certificates! ~new)))

(add-watch repo-config-fn (gensym) (fn [& _] (set-env! :repositories identity)))

(defn configure-repositories!
  "Get or set the repository configuration callback function. The function
  will be applied to all repositories added to the boot env, it should return
  the repository map with any additional configuration (like credentials, for
  example)."
  ([ ] @repo-config-fn)
  ([f] (reset! repo-config-fn f)))

(defn get-checkouts
  "FIXME"
  []
  (let [deps (->> :dependencies (get-env) (group-by first))]
    (->> @loaded-checkouts
         (reduce-kv #(assoc %1 %2 (assoc %3 :dep (first (deps %2)))) {}))))

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
  (binding [*print-level* nil
            *print-length* nil]
    (doseq [[k v] (order-set-env-keys (partition 2 kvs))]
      (let [v'  (if-not (fn? v) v (v (get-env k)))
            v'' (if-let [b (get @cli-base k)] (merge-if-coll b v') v')]
        (assert (printable-readable? v'')
                (format "value not readable by Clojure reader\n%s => %s" (pr-str k) (pr-str v'')))
        (swap! boot-env update-in [k] (partial pre-env! k) v'' @boot-env)))))

(defn merge-env!
  "Merges the new values into the current values for the given keys in the env
  map. Uses a merging strategy that is appropriate for the given key (eg. uses
  clojure.core/into for keys whose values are collections and simply replaces
  Keys whose values aren't collections)."
  [& kvs]
  (->> (partition 2 kvs)
       (mapcat (fn [[k v]] [k #(merge-or-replace % v)]))
       (apply set-env!)))

(defn get-sys-env
  "Returns the value associated with the system property k, the environment
  variable k, or not-found if neither of those are set. If not-found is the
  keyword :required, an exception will be thrown when there is no value for
  either the system property or environment variable k."
  ([ ] (merge {} (System/getenv) (System/getProperties)))
  ([k] (get-sys-env k nil))
  ([k not-found]
   (util/with-let [v ((get-sys-env) k not-found)]
     (when (= v not-found :required)
       (throw (ex-info (format "Required env var: %s" k) {}))))))

(defn set-sys-env!
  "For each key value pair in kvs the system property corresponding to the key
  is set. Keys and values must be strings, but the value can be nil or false
  to remove the system property."
  [& kvs]
  (doseq [[^String k ^String v] (partition 2 kvs)]
    (if v (System/setProperty k v) (System/clearProperty k))))

;; Defining Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro deftask
  "Define a boot task."
  [sym & forms]
  (let [[heads [bindings & tails]] (split-with (complement vector?) forms)]
    `(do
       (when-let [existing-deftask# (resolve '~sym)]
         (when (= *ns* (-> existing-deftask# meta :ns))
           (let [msg# (delay (format "deftask %s/%s was overridden\n" *ns* '~sym))]
             (boot.util/warn (if (<= @util/*verbosity* 2)
                               @msg#
                               (ex/format-exception (Exception. ^String @msg#)))))))
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
    (-> (new-fileset)
        (add-user-asset    (first (user-asset-dirs)))
        (add-user-source   (first (user-source-dirs)))
        (add-user-resource (first (user-resource-dirs)))
        (update-in [:tree] merge (:tree fileset))
        (vary-meta merge (meta fileset)))))

(defn reset-build!
  "Resets mutable build state to default values. This includes such things as
  warning counters etc., state that is relevant to a single build cycle. This
  function should be called before each build iteration."
  []
  (reset! *warnings* 0))

(defn- take-subargs [open close [x & xs :as coll]]
  (if (not= x open)
    [nil coll]
    (loop [[x & xs] xs depth 1 ret []]
      (if (not x)
        [ret []]
        (cond (= x open)  (recur xs (inc depth) (conj ret x))
              (= x close) (if (zero? (dec depth))
                            [ret xs]
                            (recur xs (dec depth) (conj ret x)))
              :else       (recur xs depth (conj ret x)))))))

(defn- construct-tasks
  "Given command line arguments (strings), constructs a task pipeline by
  resolving the task vars, calling the task constructors with the arguments
  for that task, and composing them to form the pipeline."
  [argv & {:keys [in-order]}]
  (loop [ret [] [op-str & args :as argv] argv]
    (if-not op-str
      (apply comp (filter fn? ret))
      (case op-str
        "--" (recur ret args)
        "["  (let [[argv remainder] (take-subargs "[" "]" argv)]
               (recur (conj ret (construct-tasks argv :in-order false)) remainder))
        (let [op (-> op-str symbol resolve)]
          (when-not (and op (:boot.core/task (meta op)))
            (throw (IllegalArgumentException. (format "No such task (%s)" op-str))))
          (let [spec   (:argspec (meta op))
                parsed (cli/parse-opts args spec :in-order in-order)]
            (when (seq (:errors parsed))
              (throw (IllegalArgumentException. (string/join "\n" (:errors parsed)))))
            (let [[opts argv] (if-not in-order
                                [args nil]
                                (#'cli2/separate-cli-opts args spec))]
              (recur (conj ret (apply (var-get op) opts)) argv))))))))

(defn- fileset-syncer
  "Given a seq of directories dirs, returns a stateful function of one
  argument. Each time this function is called with a fileset argument it
  updates each of the dirs such that changes to the fileset are also
  applied to them. The :link option will enable the use of hard links
  where possible. The :clean option to the constructor enables or disables
  cleaning out the target directory on initialization."
  [dirs & {:keys [clean]}]
  (let [prev   (atom nil)
        clean! (if clean empty-dir! identity)
        dirs   (delay (mapv #(doto (io/file %) .mkdirs clean!) dirs))]
    (fn [fs & {:keys [link mode]}]
      (let [link  (when link :tmp)
            [a b] [@prev (reset! prev (output-fileset fs))]]
        (mapv deref (for [d @dirs :let [p! (partial fs/patch! (fs/->path d) a b :mode mode :link)]]
                      (future (try (p! link)
                                   (catch Throwable t
                                     (if-not link (throw t) (p! nil)))))))))))

(defn- run-tasks
  "Given a task pipeline, builds the initial fileset, sets the initial build
  state, and runs the pipeline."
  [task-stack]
  (binding [*warnings* (atom 0)]
    (let [fs (commit! (reset-fileset))]
      ((task-stack #(do (sync-user-dirs!) %)) fs))))

(defn boot
  "The REPL equivalent to the command line 'boot'. If all arguments are
  strings then they are treated as if they were given on the command line.
  Otherwise they are assumed to evaluate to task middleware."
  [& argv]
  (try @(future ;; see issue #6
          (util/with-let [_ nil]
            (run-tasks
              (cond (every? fn? argv)     (apply comp argv)
                    (every? string? argv) (construct-tasks argv :in-order true)
                    :else (throw (IllegalArgumentException.
                                   "Arguments must be either all strings or all fns"))))))
       (catch ExecutionException e
         (throw (.getCause e)))
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
  [bind & body]
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

(defmacro template
  "The syntax-quote (aka quasiquote) reader macro as a normal macro. Provides
  the unquote ~ and unquote-splicing ~@ metacharacters for templating forms
  without performing symbol resolution."
  [form]
  `(bt/template ~form))

(defn gpg-decrypt
  "Uses gpg(1) to decrypt a file and returns its contents as a string. The
  :as :edn option can be passed to read the contents as an EDN form."
  [path-or-file & {:keys [as]}]
  ((case as :edn read-string identity) (gpg/decrypt path-or-file)))

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

(defn- filter-files [seq-pred files negate?]
  ((if negate? remove filter)
   #(some identity (seq-pred %)) files))

(defn file-filter
  "A file filtering function factory. FIXME: more documenting here."
  [mkpred]
  (fn [criteria files & [negate?]]
    (let [tmp?   (partial satisfies? tmpd/ITmpFile)
          ->file #(if (tmp? %) (io/file (tmp-path %)) (io/file %))
          pred (if (empty? criteria) nil ((apply juxt (mapv mkpred))))]
      (filter-files #(pred (->file %)) files negate?))))

(defn by-meta
  "This function takes two arguments: `preds` and `files`, where `preds` is a
  seq of predicates to be applied to the file metadata and `files` is a seq of
  file objects obtained from the fileset with the help of `boot.core/ls` or any
  other way. Returns a seq of files in `files` which match all of the
  predicates in `preds`. `negate?` inverts the result.

  This function will not unwrap the `File` objects from `TmpFiles`."
  [preds files & [negate?]]
  (filter-files (apply juxt preds) files negate?))

(defn not-by-meta
  "Negated version of `by-meta`.

  This function will not unwrap the `File` objects from `TmpFiles`."
  [preds files]
  (by-meta preds files true))

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
