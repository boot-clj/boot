(ns boot.pod
  (:require
    [clojure.set                  :as set]
    [clojure.string               :as string]
    [boot.util                    :as util]
    [boot.file                    :as file]
    [boot.xform                   :as xf]
    [boot.from.backtick           :as bt]
    [boot.from.io.aviso.exception :as ex]
    [clojure.java.io              :as io]
    [dynapath.util                :as dp]
    [dynapath.dynamic-classpath   :as cp])
  (:import
    [java.util.jar        JarFile]
    [java.lang.ref        WeakReference]
    [java.util            Properties UUID]
    [java.net             URL URLClassLoader URLConnection]
    [java.util.concurrent ConcurrentLinkedQueue LinkedBlockingQueue TimeUnit]
    [java.io              File]
    [java.nio.file        Files StandardCopyOption])
  (:refer-clojure :exclude [add-classpath]))

(defn extract-ids
  "Given a dependency symbol sym, returns a vector of [group-id artifact-id]."
  [sym]
  (let [[group artifact] ((juxt namespace name) sym)]
    [(or group artifact) artifact]))

(defn seal-app-classloader
  "Implements the DynamicClasspath protocol to the AppClassLoader class such
  that instances of this class will refuse attempts at runtime modification
  by libraries that do so via dynapath[1]. The system class loader is of the
  type AppClassLoader.

  The purpose of this is to ensure that Clojure libraries do not pollute the
  higher-level class loaders with classes and interfaces created dynamically
  in their Clojure runtime. This is essential for pods to work properly[2].

  This function is called during Boot's bootstrapping phase, and shouldn't
  be needed in client code under normal circumstances.

  [1]: https://github.com/tobias/dynapath
  [2]: https://github.com/clojure-emacs/cider-nrepl/blob/36333cae25fd510747321f86e2f0369fcb7b4afd/README.md#with-jboss-asjboss-eapwildfly"
  []
  (extend sun.misc.Launcher$AppClassLoader
    cp/DynamicClasspath
    (assoc cp/base-readable-addable-classpath
      :classpath-urls #(seq (.getURLs %))
      :can-add? (constantly false))))

(defn ^{:boot/from :cemerick/pomegranate} classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip] (->> tip (iterate #(.getParent %)) (take-while boolean))))

(defn ^{:boot/from :cemerick/pomegranate} modifiable-classloader?
  "Returns true iff the given ClassLoader is of a type that satisfies
   the dynapath.dynamic-classpath/DynamicClasspath protocol, and it can
   be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn ^{:boot/from :cemerick/pomegranate} add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
     (if-not (dp/add-classpath-url classloader (.toURL (.toURI (io/file jar-or-dir))))
       (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                            classloaders)))))))

(defn ^{:boot/from :cemerick/pomegranate} get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   (System/getProperty \"java.class.path\") as a seq of URL strings.

   Produces the classpath from all classloaders by default, or from a
   collection of classloaders if provided.  This allows you to easily look
   at subsets of the current classloader hierarchy, e.g.:

   (get-classpath (drop 2 (classloader-hierarchy)))"
  ([classloaders]
    (->> (reverse classloaders)
      (mapcat #(dp/classpath-urls %))
      (map str)))
  ([] (get-classpath (classloader-hierarchy))))

(defn ^{:boot/from :cemerick/pomegranate} classloader-resources
  "Returns a sequence of [classloader url-seq] pairs representing all of the
  resources of the specified name on the classpath of each classloader. If no
  classloaders are given, uses the classloader-heirarchy, in which case the
  order of pairs will be such that the first url mentioned will in most
  circumstances match what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (for [classloader (reverse classloaders)]
       [classloader (enumeration-seq
                      (.getResources ^ClassLoader classloader resource-name))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn ^{:boot/from :cemerick/pomegranate} resources
  "Returns a sequence of URLs representing all of the resources of the
   specified name on the effective classpath. This can be useful for finding
  name collisions among items on the classpath. In most circumstances, the
  first of the returned sequence will be the same as what clojure.java.io/resource
  returns."
  ([classloaders resource-name]
     (distinct (mapcat second (classloader-resources classloaders resource-name))))
  ([resource-name] (resources (classloader-hierarchy) resource-name)))

(defn- find-in-jarfile [jf path]
  (let [entries (->> jf .entries enumeration-seq
                     (filter #(.endsWith (.getName %) path)))]
    (when (< 1 (count entries))
      (throw (Exception. (format "Multiple jar entries match: .*%s" path))))
    (first entries)))

(defn pom-properties
  "Given a path or File jarpath, finds the jar's pom.properties file, reads
  it, and returns the loaded Properties object. An exception is thrown if
  multiple pom.properties files are present in the jar."
  [jarpath]
  (with-open [jarfile (JarFile. (io/file jarpath))
              props   (->> (find-in-jarfile jarfile "/pom.properties")
                           (.getInputStream jarfile))]
    (doto (Properties.)
      (.load props))))

(defn- pom-prop-map
  [props]
  (let [gid (.getProperty props "groupId")
        aid (.getProperty props "artifactId")
        ver (.getProperty props "version")]
    {:group-id    gid
     :artifact-id aid
     :project     (symbol gid aid)
     :version     ver}))

(defn dependency-loaded?
  "Given a dependency coordinate of the form [id version ...], returns a URL
  for the pom.properties file in the dependency jar, or nil if the dependency
  isn't on the classpath."
  [[project & _]]
  (let [[aid gid] (util/extract-ids project)]
    (io/resource (format "META-INF/maven/%s/%s/pom.properties" aid gid))))

(defn coord->map
  "Returns the map representation for the given dependency vector. The map
  will include :project and :version keys in addition to any other keys in
  the dependency vector (eg., :scope, :exclusions, etc)."
  [[p v & more]]
  (merge {:project p :version v} (apply hash-map more)))

(defn map->coord
  "Returns the dependency vector for the given map representation. The project
  and version will be taken from the values of the :project and :version keys
  and all other keys will be appended pairwise."
  [{:keys [project version] :as more}]
  (into [project version] (mapcat identity (dissoc more :project :version))))

(defn dependency-pom-properties
  "Given a dependency coordinate of the form [id version ...], returns a
  Properties object corresponding to the dependency jar's pom.properties file."
  [coord]
  (with-open [props (io/input-stream (dependency-loaded? coord))]
    (doto (Properties.) (.load props))))

(defn dependency-pom-properties-map
  "Given a dependency coordinate of the form [id version ...], returns a map
  of the contents of the jar's pom.properties file."
  [coord]
  (pom-prop-map (dependency-pom-properties coord)))

(defn pom-properties-map
  "Returns a map of the contents of the pom.properties pom-or-jarpath, where
  pom-or-jarpath is either a slurpable thing or a Properties object."
  [prop-or-jarpath]
  (pom-prop-map
    (if (instance? Properties prop-or-jarpath)
      prop-or-jarpath
      (with-open [pom-input-stream (io/input-stream prop-or-jarpath)]
        (doto (Properties.) (.load pom-input-stream))))))

(defn pom-xml
  "Returns a the pom.xml contents as a string. If only jarpath is given the
  jar must contain exactly one pom.xml file. If pompath is a file that exists
  the contents of that file will be returned. Otherwise, pompath must be the
  resource path of the pom.xml file in the jar."
  ([jarpath]
   (pom-xml jarpath nil))
  ([jarpath pompath]
   (if (and pompath (.exists (io/file pompath)))
     (slurp pompath)
     (with-open [jarfile (JarFile. (io/file jarpath))]
       (let [pompath (when pompath
                       (format "META-INF/maven/%s/pom.xml" pompath))
             entry   (if pompath
                       (.getJarEntry jarfile pompath)
                       (find-in-jarfile jarfile "/pom.xml"))]
         (with-open [in (.getInputStream jarfile entry)]
           (slurp in)))))))

(defn resource-last-modified
  "Returns the last modified time (long, milliseconds since epoch) of the
  classpath resource at resource-path. A result of 0 usually indicates that
  the modification time was not available for this resource."
  [resource-path]
  (let [c (.openConnection (io/resource resource-path))]
      (try (.getLastModified c)
           (finally (.. c getInputStream close)))))

(defn copy-resource
  "Copies the contents of the classpath resource at resource-path to the path or
  File out-path on the filesystem, preserving last modified times when possible.
  The copy operation is not atomic."
  [resource-path out-path]
  (let [url  (io/resource resource-path)
        outf (doto (io/file out-path) io/make-parents)]
    (with-open [in  (io/input-stream url)
                out (io/output-stream outf)]
      (io/copy in out)
      (let [mtime (resource-last-modified resource-path)]
        (when (< 0 mtime) (.setLastModified outf mtime))))))

(defn non-caching-url-input-stream
  "Returns an InputStream from the URL constructed from url-str, with caching
  disabled. This is useful for example when accessing jar entries in jars that
  may change."
  [url-str]
  (.getInputStream (doto (.openConnection (URL. url-str)) (.setUseCaches false))))

(defn copy-url
  "Copies the URL constructed from url-str to the path or File out-path. When
  the :cache option is false caching of URLs is disabled."
  [url-str out-path & {:keys [cache] :or {cache true}}]
  (with-open [in  ((if cache io/input-stream non-caching-url-input-stream) url-str)
              out (io/output-stream (doto (io/file out-path) io/make-parents))]
    (io/copy in out)))

(def env
  "This pod's boot environment."
  nil)

(def data
  "Set by boot.App/newCore, may be a ConcurrentHashMap for sharing data between
  instances of Boot that are running inside of Boot."
  nil)

(def pods
  "A WeakHashMap whose keys are all of the currently running pods."
  nil)

(def pod-id
  "Each pod is numbered in the order in which it was created."
  nil)

(def this-pod
  "A WeakReference to this pod's shim instance."
  nil)

(def worker-pod
  "A reference to the boot worker pod. All pods share access to the worker
  pod singleton instance."
  nil)

(def shutdown-hooks
  "Atom containing shutdown hooks to be performed at exit. This is used instead
  of Runtime.getRuntime().addShutdownHook() by boot so that these hooks can be
  called without exiting the JVM process, for example when boot is running in
  boot. See #'boot.pod/add-shutdown-hook! for more info."
  (atom nil))

(defn set-pods!         [x] (alter-var-root #'pods        (constantly x)))
(defn set-data!         [x] (alter-var-root #'data        (constantly x)))
(defn set-pod-id!       [x] (alter-var-root #'pod-id      (constantly x)))
(defn set-this-pod!     [x] (alter-var-root #'this-pod    (constantly x)))
(defn set-worker-pod!   [x] (alter-var-root #'worker-pod  (constantly x)))

(defn get-pods
  "Returns a seq of pod references whose names match name-or-pattern, which
  can be a string or a Pattern. Strings are matched by equality, and Patterns
  by re-find. The unique? option, if given, will cause an exception to be
  thrown unless exactly one pod matches."
  ([name-or-pattern]
   (get-pods name-or-pattern false))
  ([name-or-pattern unique?]
   (let [p? (-> (if (string? name-or-pattern) = re-find)
                (partial name-or-pattern)
                (comp (memfn getName)))
         [p & ps] (->> pods (map key) (filter p?))]
     (when (and unique? (not p))
       (throw (Exception. (format "No pod matches: %s" name-or-pattern))))
     (when (and unique? (seq ps))
       (throw (Exception. (format "More than one pod name matches: %s" name-or-pattern))))
     (if unique? p (cons p ps)))))

(defn add-shutdown-hook!
  "Adds f to the global queue of shutdown hooks for this instance of boot. Note
  that boot may be running inside another instance of boot, so shutdown hooks
  must be handled carefully as the JVM will not necessarily exit when this boot
  instance is finished.
  
  Functions added via add-shutdown-hook! will be processed at the correct time
  (i.e. when boot is finished in the case of nested instances of boot, or when
  the JVM exits otherwise)."
  [f]
  (if (not= 1 pod-id)
    (.offer @shutdown-hooks f)
    (->> f Thread. (.addShutdownHook (Runtime/getRuntime)))))

(defn send!
  "This is ALPHA status, it may change, be renamed, or removed."
  [form]
  (let [form (binding [*print-meta* true] (pr-str form))]
    `(read-string (boot.App/getStash ~(boot.App/setStash form)))))

(defn eval-fn-call
  "Given an expression of the form (f & args), resolves f and applies f to args."
  [[f & args]]
  (when-let [ns (namespace f)] (require (symbol ns)))
  (if-let [f (resolve f)]
    (apply f args)
    (throw (Exception. (format "can't resolve symbol (%s)" f)))))

(defmacro with-invoke-in
  "Given a pod, a fully-qualified symbol sym, and args which are Java objects,
  invokes the function denoted by sym with the given args. This is a low-level
  interface--args are not serialized before being passed to the pod and the
  result is not deserialized before being returned. Passing Clojure objects as
  arguments or returning Clojure objects from the pod will result in undefined
  behavior.

  This macro correctly handles the case where pod is the current pod without
  thread binding issues: in this case the invocation will be done in another
  thread."
  [pod [sym & args]]
  `(let [pod# ~pod]
     (if (not= pod# (.get this-pod))
       (.invoke pod# ~(str sym) ~@args)
       (deref (future (.invoke pod# ~(str sym) ~@args))))))

(defmacro with-invoke-worker
  "Like with-invoke-in, invoking the function in the worker pod."
  [[sym & args]]
  `(with-invoke-in worker-pod (~sym ~@args)))

(defn pod-name
  "Returns pod's name if called with one argument, sets pod's name to new-name
  and returns new-name if called with two arguments."
  ([pod]
   (.getName pod))
  ([pod new-name]
   (.setName pod new-name) new-name))

(defn call-in*
  "Low-level interface by which expressions are evaluated in other pods. The
  two-arity version is invoked in the caller with a pod instance and an expr
  form. The form is serialized and the one-arity version is invoked in the
  pod with the serialized expr, which is deserialized and evaluated. The result
  is then serialized and returned to the two-arity where it is deserialized
  and returned to the caller. The *print-meta* binding determines whether
  metadata is transmitted between pods.

  The expr is expected to be of the form (f & args). It is evaluated in the
  pod by resolving f and applying it to args.
  
  Note: Since forms must be serialized to pass from one pod to another it is
  not always appropriate to include metadata, as metadata may contain eg. File
  objects which are not printable/readable by Clojure."
  ([expr]
     (let [{:keys [meta? expr]} (read-string expr)]
       (binding [*print-meta* meta?]
         (pr-str (eval-fn-call expr)))))
  ([pod expr]
     (let [arg (pr-str {:meta? *print-meta* :expr expr})
           ret (with-invoke-in pod (boot.pod/call-in* arg))]
       (util/guard (read-string ret)))))

(defmacro with-call-in
  "Given a pod and an expr of the form (f & args), resolves f in the pod,
  applies it to args, and returns the result to the caller. The expr may be a
  template containing the ~ (unqupte) and ~@ (unquote-splicing) reader macros.
  These will be evaluated in the calling scope and substituted in the template
  like the ` (syntax-quote) reader macro.

  Note: Unlike syntax-quote, no name resolution is done on the template forms.

  Note2: The macro returned value will be nil unless it is
  printable/readable. For instance, returning File objects will not work
  as they are not printable/readable by Clojure."
  [pod expr]
  `(if-not ~pod
     (eval-fn-call (bt/template ~expr))
     (call-in* ~pod (bt/template ~expr))))

(defmacro with-call-worker
  "Like with-call-in, evaluating expr in the worker pod."
  [expr]
  `(with-call-in worker-pod ~expr))

(defn pom-xml-map
  "Returns a map of pom data from the pom.xml in the jar specified jarpath,
  which can be a string or File. If pompath is not specified there must be
  exactly one pom.xml in the jar. Otherwise, the pom.xml will be extracted
  from the jar by the resource path specified by pompath."
  ([jarpath]
   (pom-xml-map jarpath nil))
  ([jarpath pompath]
   (with-call-worker
     (boot.pom/pom-xml-parse-string
       ~(pom-xml (io/file jarpath) pompath)))))

(defn eval-in*
  "Low-level interface by which expressions are evaluated in other pods. The
  two-arity version is invoked in the caller with a pod instance and an expr
  form. The form is serialized and the one-arity version is invoked in the
  pod with the serialized expr, which is deserialized and evaluated. The result
  is then serialized and returned to the two-arity where it is deserialized
  and returned to the caller. The *print-meta* binding determines whether
  metadata is transmitted between pods.

  Unlike call-in*, expr can be any expression, without the restriction that it
  be of the form (f & args).

  Note: Since forms must be serialized to pass from one pod to another it is
  not always appropriate to include metadata, as metadata may contain eg. File
  objects which are not printable/readable by Clojure."
  ([expr]
     (let [{:keys [meta? expr]} (read-string expr)]
       (binding [*print-meta* meta?]
         (pr-str (eval expr)))))
  ([pod expr]
     (let [arg (pr-str {:meta? *print-meta* :expr expr})
           ret (with-invoke-in pod (boot.pod/eval-in* arg))]
       (util/guard (read-string ret)))))

(defmacro with-eval-in
  "Given a pod and an expr, evaluates the body in the pod and returns the
  result to the caller. The body may be a template containing the ~ (unqupte)
  and ~@ (unquote-splicing) reader macros. These will be evaluated in the
  calling scope and substituted in the template like the ` (syntax-quote)
  reader macro.

  Note: Unlike syntax-quote, no name resolution is done on the template
  forms.

  Note2: The macro returned value will be nil unless it is printable/readable.
  For instance, returning File objects will not work as they are not printable
  and readable by Clojure."
  [pod & body]
  `(if-not ~pod
     (eval (bt/template (do ~@body)))
     (eval-in* ~pod (bt/template (do ~@body)))))

(defmacro with-eval-worker
  "Like with-eval-in, evaluates the body in the worker pod."
  [& body]
  `(with-eval-in worker-pod ~@body))

(defn require-in
  "Evaluates (require 'ns) in the pod. Avoid this function."
  [pod ns]
  (doto pod
    (with-eval-in
      (require '~(symbol (str ns))))))

(defn eval-in-callee
  "Implementation detail. This is the callee side of the boot.pod/with-pod
  mechanism (i.e. this is the function that's called in the pod to perform
  the work)."
  [caller-pod callee-pod expr]
  (eval (xf/->clj caller-pod callee-pod expr :for-eval true)))

(defn eval-in-caller
  "Implementation detail. This is the caller side of the boot.pod/with-pod
  mechanism (i.e. this is the function that's called in the caller pod to
  send work to the callee pod so the callee pod can perform the work)."
  [caller-pod callee-pod expr]
  (xf/->clj callee-pod caller-pod
            (with-invoke-in (.get callee-pod)
              (boot.pod/eval-in-callee caller-pod callee-pod expr))))

(defmacro with-pod
  "Like boot.pod/with-eval-in but with the ability to pass most types between
  the caller and the pod.

  Supports the normal types that are recognized by the Clojure reader, plus
  functions, records, and all Java types. (The namespace in which the record
  type is defined must be on the classpath in the pod.)"
  [pod & body]
  `(if-not ~pod
     (eval (bt/template (do ~@body)))
     (eval-in-caller this-pod (WeakReference. ~pod) (bt/template (do ~@body)))))

(defmacro with-worker
  "Like with-pod, evaluates the body in the worker pod."
  [& body]
  `(with-pod worker-pod ~@body))

(defn resolve-dependencies
  "Returns a seq of maps of the form {:jar <path> :dep <dependency vector>}
  corresponding to the fully resolved dependency graph as specified in the
  env, where env is the boot environment (see boot.core/get-env). The seq of
  dependencies includes all transitive dependencies."
  [env]
  (with-call-worker (boot.aether/resolve-dependencies ~env)))

(defn resolve-release-versions
  "Given environment map env, replaces the versions of dependencies that are
  specified with the special Maven RELEASE version with the concrete versions
  of the resolved dependencies."
  [env]
  (with-call-worker (boot.aether/resolve-release-versions ~env)))

(defn resolve-dependency-jars
  "Returns a seq of File objects corresponding to the jar files associated with
  the fully resolved dependency graph as specified in the env, where env is the
  boot environment (see boot.core/get-env). If ignore-clj? is specified Clojure
  will be excluded from the result (the clojure dependency is identified by the
  BOOT_CLOJURE_NAME environment setting, which defaults to org.clojure.clojure)."
  [env & [ignore-clj?]]
  (let [clj-dep (symbol (boot.App/config "BOOT_CLOJURE_NAME"))
        rm-clj  (if-not ignore-clj?
                  identity
                  (partial remove #(= clj-dep (first (:dep %)))))]
    (->> env resolve-dependencies rm-clj (map (comp io/file :jar)))))

(defn resolve-nontransitive-dependencies
  "Returns a seq of maps of the form {:jar <path> :dep <dependency vector>}
  for the dependencies of dep, excluding transitive dependencies (i.e. the
  dependencies of dep's dependencies)."
  [env dep]
  (with-call-worker (boot.aether/resolve-nontransitive-dependencies ~env ~dep)))

(defn resolve-dependency-jar
  "Returns the path to the jar file associated with the dependency specified
  by coord, given the boot environment configuration env."
  [env coord]
  (let [coord (util/canonical-coord coord)]
    (->> [coord] (assoc env :dependencies) resolve-dependencies
      (filter #(= (first coord) (first (:dep %)))) first :jar)))

(defn outdated
  "Returns a seq of dependency vectors corresponding to outdated dependencies
  in the dependency graph associated with the boot environment env. If the
  :snapshots option is given SNAPSHOT versions will be considered, otherwise
  only release versions will be considered."
  [env & {:keys [snapshots]}]
  (with-call-worker (boot.aether/update-always!))
  (let [v+ (if snapshots "(0,)" "RELEASE")]
    (->> (for [[p v & _ :as coord] (->> env :dependencies (map util/canonical-coord))
                                   :when (not (= v "LATEST"))]
           (util/guard
             (let [env' (-> env (assoc :dependencies [(assoc coord 1 v+)]))
                   [p' v' & _ :as coord'] (->> (map :dep (resolve-dependencies env'))
                                               (filter #(= p (first %))) first)]
               (and (= p p') (not= v v') coord'))))
         (filter identity))))

(defn apply-exclusions
  "Merges the seq of dependency ids excl into the :exclusions of the dependency
  vector dep, creating the :exclusions key and deduplicating as necessary."
  [excl [p v & opts :as dep]]
  (let [excl'  (:exclusions (apply hash-map opts))
        excl'' (-> excl' set (into excl) (disj p) vec)
        excl''' (if (empty? excl'') [] [:exclusions excl''])]
    (if (empty? excl')
      (into dep excl''')
      (->> (partition 2 opts)
           (mapcat #(if-not (= (first %) :exclusions) % excl'''))
           (into [p v])))))

(defn apply-global-exclusions
  "Merges the seq of dependency ids excl into all dependency vectors in deps.
  See apply-exclusions."
  [excl deps]
  (mapv (partial apply-exclusions excl) deps))

(defn add-dependencies
  "Resolve dependencies specified in the boot environment env and add their
  jars to the classpath."
  [env]
  (doseq [jar (resolve-dependency-jars env true)] (add-classpath jar)))

(defn add-dependencies-in
  "Resolve dependencies specified in the boot environment env and add their
  jars to the classpath in the pod."
  [pod env]
  (with-call-in pod
    (boot.pod/add-dependencies ~env)))

(defn add-dependencies-worker
  "Resolve dependencies specified in the boot environment env and add their
  jars to the classpath in the worker pod."
  [env]
  (add-dependencies-in worker-pod env))

(defn jar-entries*
  "Returns a vector containing vectors of the form [name url-string] for each
  entry in the jar path-or-jarfile, which can be a string or File."
  [path-or-jarfile]
  (when path-or-jarfile
    (let [f    (io/file path-or-jarfile)
          path (.getPath f)]
      (when (.endsWith path ".jar")
        (when (and (.exists f) (.isFile f))
          (with-open [jf (JarFile. f)]
            (->> (.entries jf)
                 enumeration-seq
                 (keep #(when-not (.isDirectory %)
                          (let [name (.getName %)]
                            [name (->> (io/file (io/file (str path "!")) name)
                                       .toURI .toURL .toString (str "jar:"))])))
                 (into []))))))))

(def jar-entries-memoized*
  "Memoized version of jar-entries*."
  (memoize jar-entries*))

(defn jar-entries
  "Given a path to a jar file, returns a list of [resource-path, resource-url]
  string pairs corresponding to all entries contained the jar contains."
  [path-or-jarfile & {:keys [cache] :or {cache true}}]
  ((if cache jar-entries-memoized* jar-entries*) path-or-jarfile))

(def standard-jar-exclusions
  "Entries matching these Patterns will not be extracted from jars when they
  are exploded during uberjar construction."
  #{#"(?i)^META-INF/[^/]*\.(MF|SF|RSA|DSA)$"
    #"(?i)^META-INF/INDEX.LIST$"})

(defn into-merger
  "Reads the InputStreams prev and new as EDN, uses clojure.core/into to merge
  the data from new into prev, and writes the result to the OutputStream out."
  [prev new out]
  (let [read' (comp read-string slurp io/reader)]
    (-> (read' prev)
      (into (read' new))
      pr-str
      (io/copy out))))

(defn concat-merger
  "Reads the InputStreams prev and new as strings and appends new to prev,
  separated by a single newline character. The result is written to the
  OutputStream out."
  [prev new out]
  (let [read' (comp slurp io/reader)]
    (-> (read' prev)
        (str \newline (read' new))
        (io/copy out))))

(defn first-wins-merger
  "Writes the InputStream prev to the OutputStream out."
  [prev _ out]
  (io/copy prev out))

(def standard-jar-mergers
  "A vector containing vectors of the form [<path-matcher> <merger-fn>]. These
  pairs will be used to decide how to merge conflicting entries when exploding
  or creating jar files. See boot.task.built-in/uber for more info."
  [[#"data_readers.clj$"    into-merger]
   [#"META-INF/services/.*" concat-merger]
   [#".*"                   first-wins-merger]])

(defn unpack-jar
  "Explodes the jar identified by the string or File jar-path, copying all jar
  entries to the directory given by the string or File dest-dir. The directory
  will be created if it does not exist and jar entry modification times will
  be preserved. Files will not be written atomically."
  [jar-path dest-dir & opts]
  (with-open [jf (JarFile. jar-path)]
    (doseq [entry (enumeration-seq (.entries jf))
            :when (not (.isDirectory entry))]
      (let [ent-name (.getName entry)
            out-file (doto (io/file dest-dir ent-name) io/make-parents)]
        (try (util/dbug* "Unpacking %s from %s...\n" ent-name (.getName jf))
             (with-open [in-stream  (.getInputStream jf entry)
                         out-stream (io/output-stream out-file)]
               (io/copy in-stream out-stream))
             (.setLastModified out-file (.getTime entry))
             (catch Exception err
               (util/warn "Error extracting %s:%s: %s\n" jar-path entry err)))))))

(defn jars-dep-graph
  "Returns a dependency graph for all jar file dependencies specified in the
  boot environment map env, including transitive dependencies."
  [env]
  (with-call-worker (boot.aether/jars-dep-graph ~env)))

(defn jars-in-dep-order
  "Returns a seq of all jar file dependencies specified in the boot environment
  map env, including transitive dependencies, and in dependency order.
 
  Dependency order means, eg. if jar B depends on jar A then jar A will appear
  before jar B in the returned list."
  [env]
  (map io/file (with-call-worker (boot.aether/jars-in-dep-order ~env))))

(defn copy-dependency-jar-entries
  "Resolve all dependencies and transitive dependencies of the dependency given
  by the dep vector coord (given the boot environment configuration env), and
  explode them into the outdir directory. The outdir directory will be created
  if necessary and last modified times of jar entries will be preserved. If the
  optional Patterns, regexes, are specified only entries whose paths match at
  least one regex will be extracted to outdir."
  [env outdir coord & regexes]
  (let [keep? (if-not (seq regexes)
                (constantly true)
                (apply some-fn (map #(partial re-find %) regexes)))]
    (with-open [jf (JarFile. (resolve-dependency-jar env coord))]
      (doseq [entry (enumeration-seq (.entries jf))
              :let [entry-name (.getName entry)]
              :when (keep? entry-name)]
        (let [out-file (doto (io/file outdir entry-name) io/make-parents)]
          (with-open [in (.getInputStream jf entry)
                      out (io/output-stream out-file)]
            (io/copy in out))
          (.setLastModified out-file (.getTime entry)))))))

(defn lifecycle-pool
  "Creates a function implementing a lifecycle protocol on a pool of stateful
  objects. The pool will attempt to maintain at least size objects, creating
  new objects via the create function as needed. The objects are retired when
  no longer needed, using the given destroy function. The :priority option can
  be given to specify the priority of the worker thread (default NORM_PRIORITY).

  The pool maintains a queue of objects with the head of the queue being the
  \"current\" object. The pool may be \"refreshed\": the current object is
  destroyed and the next object in the queue is promoted to current object.

  The returned function accepts one argument, which can be :shutdown, :take,
  or :refresh, or no arguments.

    none          Returns the current object.

    :shutdown     Stop worker thread and destroy all objects in the pool.

    :take         Remove the current object from the pool and return it to
                  the caller without destroying it. The next object in the
                  pool will be promoted. Note that it is the responsibility
                  of the caller to properly dispose of the returned object.

    :refresh      Destroy the current object and promote the next object."
  [size create destroy & {:keys [priority]}]
  (let [run? (atom true)
        pri  (or priority Thread/NORM_PRIORITY)
        q    (LinkedBlockingQueue. (int size))
        poll #(.poll % 1 TimeUnit/SECONDS)
        putp #(util/with-let [p (promise)] (.put % p))
        fill #(util/while-let [p (and @run? (putp q))]
                (deliver p (when @run? (create))))
        peek #(loop []
                (when @run?
                  (or (.peek %)
                      (do (Thread/sleep 10) (recur)))))
        take #(deref (or (peek q) (delay)))
        swap #(destroy @(.take q))
        stop #(future
                (reset! run? false)
                (util/while-let [p (poll q)]
                  (destroy @p)))]
    (doto (Thread. fill) (.setDaemon true) (.setPriority pri) .start)
    (fn
      ([] (take))
      ([op] (case op
              :shutdown (stop)
              :take     @(.take q)
              :refresh  (do (swap) (take)))))))

(defn- init-pod!
  [env pod]
  (doto pod
    (require-in "boot.pod")
    (with-invoke-in (boot.pod/set-worker-pod! worker-pod))
    (with-eval-in
      (require 'boot.util 'boot.pod)
      (reset! boot.util/*verbosity* ~(deref util/*verbosity*))
      (alter-var-root #'boot.pod/env (constantly '~env))
      (create-ns 'pod)
      (dosync (alter @#'clojure.core/*loaded-libs* conj 'pod))
      (alter-var-root #'*ns* (constantly (the-ns 'pod)))
      (clojure.core/refer-clojure))))

(defn default-dependencies
  "Adds default dependencies given by deps to the :dependencies in env, but
  favoring dependency versions in env over deps in case of conflict."
  [deps {:keys [dependencies] :as env}]
  (let [not-given (set/difference (set (map first deps))
                                  (set (map first dependencies)))
        dfl-deps  (vec (filter (comp not-given first) deps))]
    (assoc env :dependencies (into dfl-deps dependencies))))

(defmacro caller-namespace
  "When this macro is used in a function, it returns the namespace of the
  caller of the function."
  []
  `(let [e# (Exception. "")]
     (-> (->> (ex/expand-stack-trace e#)
              (map :name)
              (remove string/blank?)
              second)
         (.replaceAll "/.*$" ""))))

(defn make-pod
  "Returns a newly constructed pod. A boot environment configuration map, env,
  may be given to initialize the pod with dependencies, directories, etc.

  The :name option sets the name of the pod. Default uses the namespace of
  the caller as the pod's name.

  The :data option sets the boot.pod/data object in the pod. The data object
  is used to coordinate different pods, for example the data object could be
  a BlockingQueue or ConcurrentHashMap shared with other pods. Default uses
  boot.pod/data from the current pod."
  ([] (init-pod! env (boot.App/newPod nil data)))
  ([{:keys [directories dependencies] :as env} & {:keys [name data]}]
     (let [dirs (map io/file directories)
           cljname (or (boot.App/getClojureName) "org.clojure/clojure")
           dfl  [['boot/pod (boot.App/getBootVersion)]
                 [(symbol cljname) (clojure-version)]]
           env  (default-dependencies dfl env)
           jars (resolve-dependency-jars env)
           urls (concat dirs jars)]
       (doto (->> (into-array java.io.File urls)
                  (boot.App/newShim nil (or data boot.pod/data))
                  (init-pod! env))
         (pod-name (or name (caller-namespace)))))))

(defn make-pod-cp
  "Returns a new pod with the given classpath. Classpath may be a collection
  of java.lang.String or java.io.File objects.

  The :name and :data options are the same as for boot.pod/make-pod.

  NB: The classpath must include Clojure (either clojure.jar or directories),
  but must not include Boot's pod.jar, Shimdandy's impl, or Dynapath. These
  are needed to bootstrap the pod, have no transitive dependencies, and are
  added automatically."
  [classpath & {:keys [name data]}]
  (doto (->> (assoc env :dependencies [['boot/pod (boot.App/getBootVersion)]])
             (resolve-dependency-jars)
             (into (map io/file classpath))
             (into-array java.io.File)
             (boot.App/newShim nil (or data boot.pod/data))
             (init-pod! nil))
    (pod-name (or name (caller-namespace)))))

(defn destroy-pod
  "Closes open resources held by the pod, making the pod eligible for GC."
  [pod]
  (when pod
    (.close pod)
    (.. pod getClassLoader close)))

(defn pod-pool
  "Creates a pod pool service. The service maintains a pool of prebuilt pods
  with a current active pod and a number of pods in reserve, warmed and ready
  to go (it takes ~2s to load clojure.core into a pod).

  Pool Service API:
  -----------------

  The pod-pool function returns a pod service instance, which is itself a
  Clojure function. The pod service function can be called with no arguments
  or with :refresh or :shutdown.

  Calling the function with no arguments produces a reference to the current
  pod. Expressions can be evaluated in this pod via with-eval-in, etc.
  
  Calling the function with the :refresh argument swaps out the current pod,
  destroys it, and promotes a pod from the reserve pool. A new pod is created
  asynchronously to replenish the reserve pool.

  Calling the function with the :shutdown argument destroys all pods in the
  pool and shuts down the service.

  Options:
  --------

  :size       The total number of pods to be maintained in the pool. Default
              size is 2.
  :init       A function that is called when a new pod is created. Takes the
              new pod as an argument, is evaluated for side effects only.
  :destroy    A function that is called when a pod is destroyed. Takes the pod
              to be destroyed as an argument, is evaluated for side effects
              before pod is destroyed."
  [env & {:keys [size init destroy]}]
  (let [size    (or size 2)
        init    (if-not init #(make-pod env) #(doto (make-pod env) init))
        killpod (fn [pod] (future (destroy-pod pod)))
        destroy (if-not destroy killpod #(doto % destroy killpod))]
    (lifecycle-pool size init destroy)))
