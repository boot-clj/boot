(ns boot.pod
  (:require
    [clojure.set                :as set]
    [clojure.string             :as string]
    [boot.util                  :as util]
    [boot.file                  :as file]
    [boot.from.backtick         :as bt]
    [boot.from.digest           :as digest]
    [boot.from.io.aviso.exception :as ex]
    [clojure.java.io            :as io]
    [dynapath.util              :as dp]
    [dynapath.dynamic-classpath :as cp])
  (:import
    [java.util.jar        JarFile]
    [java.util            Properties UUID]
    [java.net             URL URLClassLoader URLConnection]
    [java.util.concurrent ConcurrentLinkedQueue LinkedBlockingQueue TimeUnit]
    [java.io              File]
    [java.nio.file        Files StandardCopyOption])
  (:refer-clojure :exclude [add-classpath]))

(defn extract-ids
  [sym]
  (let [[group artifact] ((juxt namespace name) sym)]
    [(or group artifact) artifact]))

(defn seal-app-classloader
  "see https://github.com/clojure-emacs/cider-nrepl#with-immutant"
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
  "Returns a sequence of [classloader url-seq] pairs representing all
   of the resources of the specified name on the classpath of each
   classloader. If no classloaders are given, uses the
   classloader-heirarchy, in which case the order of pairs will be
   such that the first url mentioned will in most circumstances match
   what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (for [classloader (reverse classloaders)]
       [classloader (enumeration-seq
                      (.getResources ^ClassLoader classloader resource-name))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn ^{:boot/from :cemerick/pomegranate} resources
  "Returns a sequence of URLs representing all of the resources of the
   specified name on the effective classpath. This can be useful for
   finding name collisions among items on the classpath. In most
   circumstances, the first of the returned sequence will be the same
   as what clojure.java.io/resource returns."
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
  [[project & _]]
  (let [[aid gid] (util/extract-ids project)]
    (io/resource (format "META-INF/maven/%s/%s/pom.properties" aid gid))))

(defn dependency-pom-properties
  [coord]
  (with-open [props (io/input-stream (dependency-loaded? coord))]
    (doto (Properties.) (.load props))))

(defn dependency-pom-properties-map
  [coord]
  (pom-prop-map (dependency-pom-properties coord)))

(defn pom-properties-map
  [prop-or-jarpath]
  (pom-prop-map
    (if (instance? Properties prop-or-jarpath)
      prop-or-jarpath
      (with-open [pom-input-stream (io/input-stream prop-or-jarpath)]
        (doto (Properties.) (.load pom-input-stream))))))

(defn pom-xml
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

(defn copy-resource
  [resource-path out-path]
  (with-open [in  (io/input-stream (io/resource resource-path))
              out (io/output-stream (doto (io/file out-path) io/make-parents))]
    (io/copy in out)))

(defn non-caching-url-input-stream
  [url-str]
  (.getInputStream (doto (.openConnection (URL. url-str)) (.setUseCaches false))))

(defn copy-url
  [url-str out-path & {:keys [cache] :or {cache true}}]
  (with-open [in  ((if cache io/input-stream non-caching-url-input-stream) url-str)
              out (io/output-stream (doto (io/file out-path) io/make-parents))]
    (io/copy in out)))

(def env            nil)
(def data           nil)
(def pods           nil)
(def pod-id         nil)
(def worker-pod     nil)
(def shutdown-hooks (atom nil))

(defn set-pods!         [x] (alter-var-root #'pods        (constantly x)))
(defn set-data!         [x] (alter-var-root #'data        (constantly x)))
(defn set-pod-id!       [x] (alter-var-root #'pod-id      (constantly x)))
(defn set-worker-pod!   [x] (alter-var-root #'worker-pod  (constantly x)))

(defn get-pods
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
  [[f & args]]
  (when-let [ns (namespace f)] (require (symbol ns)))
  (if-let [f (resolve f)]
    (apply f args)
    (throw (Exception. (format "can't resolve symbol (%s)" f)))))

(defn call-in*
  ([expr]
     (let [{:keys [meta? expr]} (read-string expr)]
       (binding [*print-meta* meta?]
         (pr-str (eval-fn-call expr)))))
  ([pod expr]
     (let [ret (.invoke pod "boot.pod/call-in*"
                 (pr-str {:meta? *print-meta* :expr expr}))]
       (util/guard (read-string ret)))))

(defmacro with-call-in
  [pod expr]
  `(if-not ~pod
     (eval-fn-call (bt/template ~expr))
     (call-in* ~pod (bt/template ~expr))))

(defmacro with-call-worker
  [expr]
  `(with-call-in worker-pod ~expr))

(defn pom-xml-map
  ([jarpath]
   (pom-xml-map jarpath nil))
  ([jarpath pompath]
   (with-call-worker
     (boot.pom/pom-xml-parse-string
       ~(pom-xml (io/file jarpath) pompath)))))

(defn eval-in*
  ([expr]
     (let [{:keys [meta? expr]} (read-string expr)]
       (binding [*print-meta* meta?]
         (pr-str (eval expr)))))
  ([pod expr]
     (let [ret (.invoke pod "boot.pod/eval-in*"
                 (pr-str {:meta? *print-meta* :expr expr}))]
       (util/guard (read-string ret)))))

(defmacro with-eval-in
  [pod & body]
  `(if-not ~pod
     (eval (bt/template (do ~@body)))
     (eval-in* ~pod (bt/template (do ~@body)))))

(defmacro with-eval-worker
  [& body]
  `(with-eval-in worker-pod ~@body))

(defn require-in
  [pod ns]
  (doto pod
    (with-eval-in
      (require '~(symbol (str ns))))))

(defn canonical-coord
  [[id & more :as coord]]
  (let [[ns nm] ((juxt namespace name) id)]
    (assoc-in (vec coord) [0] (if (not= ns nm) id (symbol nm)))))

(defn resolve-dependencies
  [env]
  (with-call-worker (boot.aether/resolve-dependencies ~env)))

(defn resolve-dependency-jars
  [env & [ignore-clj?]]
  (let [clj-dep (symbol (boot.App/config "BOOT_CLOJURE_NAME"))
        rm-clj  (if-not ignore-clj?
                  identity
                  (partial remove #(= clj-dep (first (:dep %)))))]
    (->> env resolve-dependencies rm-clj (map (comp io/file :jar)))))

(defn resolve-nontransitive-dependencies
  [env dep]
  (with-call-worker (boot.aether/resolve-nontransitive-dependencies ~env ~dep)))

(defn resolve-dependency-jar
  [env coord]
  (let [coord (canonical-coord coord)]
    (->> [coord] (assoc env :dependencies) resolve-dependencies
      (filter #(= (first coord) (first (:dep %)))) first :jar)))

(defn outdated
  [env & {:keys [snapshots]}]
  (with-call-worker (boot.aether/update-always!))
  (let [v+ (if snapshots "(0,)" "RELEASE")]
    (->> (for [[p v & _ :as coord] (->> env :dependencies (map canonical-coord))
                                   :when (not (= v "LATEST"))]
           (util/guard
             (let [env' (-> env (assoc :dependencies [(assoc coord 1 v+)]))
                   [p' v' & _ :as coord'] (->> (map :dep (resolve-dependencies env'))
                                               (filter #(= p (first %))) first)]
               (and (= p p') (not= v v') coord'))))
         (filter identity))))

(defn apply-exclusions
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
  [excl deps]
  (mapv (partial apply-exclusions excl) deps))

(defn add-dependencies
  [env]
  (doseq [jar (resolve-dependency-jars env true)] (add-classpath jar)))

(defn add-dependencies-in
  [pod env]
  (with-call-in pod
    (boot.pod/add-dependencies ~env)))

(defn add-dependencies-worker
  [env]
  (add-dependencies-in worker-pod env))

(defn jar-entries*
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

(def jar-entries-memoized* (memoize jar-entries*))

(defn jar-entries
  "Given a path to a jar file, returns a list of [resource-path, resource-url]
  string pairs corresponding to all entries contained the jar contains."
  [path-or-jarfile & {:keys [cache] :or {cache true}}]
  ((if cache jar-entries-memoized* jar-entries*) path-or-jarfile))

(def standard-jar-exclusions
  #{#"(?i)^META-INF/[^/]*\.(MF|SF|RSA|DSA)$"
    #"(?i)^META-INF/INDEX.LIST$"})

(defn into-merger [prev new out]
  (let [read' (comp read-string slurp io/reader)]
    (-> (read' prev)
      (into (read' new))
      pr-str
      (io/copy out))))

(defn concat-merger [prev new out]
  (let [read' (comp slurp io/reader)]
    (-> (read' prev)
        (str \newline (read' new))
        (io/copy out))))

(defn first-wins-merger [prev _ out]
  (io/copy prev out))

(def standard-jar-mergers
  [[#"data_readers.clj$"    into-merger]
   [#"META-INF/services/.*" concat-merger]
   [#".*"                   first-wins-merger]])

(defn unpack-jar
  [jar-path dest-dir & opts]
  (with-open [jf (JarFile. jar-path)]
    (doseq [entry (enumeration-seq (.entries jf))
            :when (not (.isDirectory entry))]
      (let [ent-name (.getName entry)
            out-file (doto (io/file dest-dir ent-name) io/make-parents)]
        (try (util/dbug "Unpacking %s from %s...\n" ent-name (.getName jf))
             (with-open [in-stream  (.getInputStream jf entry)
                         out-stream (io/output-stream out-file)]
               (io/copy in-stream out-stream))
             (.setLastModified out-file (.getTime entry))
             (catch Exception err
               (util/warn "Error extracting %s:%s: %s\n" jar-path entry err)))))))

(defn jars-dep-graph
  [env]
  (with-call-worker (boot.aether/jars-dep-graph ~env)))

(defn jars-in-dep-order
  [env]
  (map io/file (with-call-worker (boot.aether/jars-in-dep-order ~env))))

(defn copy-dependency-jar-entries
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
    (.invoke "boot.pod/set-worker-pod!" worker-pod)
    (with-eval-in
      (require 'boot.util 'boot.pod)
      (reset! boot.util/*verbosity* ~(deref util/*verbosity*))
      (alter-var-root #'boot.pod/env (constantly '~env))
      (create-ns 'pod)
      (dosync (alter @#'clojure.core/*loaded-libs* conj 'pod))
      (alter-var-root #'*ns* (constantly (the-ns 'pod)))
      (clojure.core/refer-clojure))))

(defn default-dependencies
  [deps {:keys [dependencies] :as env}]
  (let [not-given (set/difference (set (map first deps))
                                  (set (map first dependencies)))
        dfl-deps  (vec (filter (comp not-given first) deps))]
    (assoc env :dependencies (into dfl-deps dependencies))))

(defmacro caller-namespace
  []
  `(let [e# (Exception. "")]
     (-> (->> (ex/expand-stack-trace e#)
              (map :name)
              (remove string/blank?)
              second)
         (.replaceAll "/.*$" ""))))

(defn make-pod
  ([] (init-pod! env (boot.App/newPod nil data)))
  ([{:keys [directories dependencies] :as env}]
     (let [dirs (map io/file directories)
           cljname (or (boot.App/getClojureName) "org.clojure/clojure")
           dfl  [['boot/pod (boot.App/getBootVersion)]
                 [(symbol cljname) (clojure-version)]]
           env  (default-dependencies dfl env)
           jars (resolve-dependency-jars env)
           urls (concat dirs jars)]
       (doto (->> (into-array java.io.File urls)
                  (boot.App/newShim nil data)
                  (init-pod! env))
         (.setName (caller-namespace))))))

(defn destroy-pod
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
