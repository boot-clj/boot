(ns boot.pod
  (:require
   [boot.util                  :as util]
   [boot.from.backtick         :as bt]
   [clojure.java.io            :as io]
   [dynapath.util              :as dp]
   [dynapath.dynamic-classpath :as cp])
  (:import
   [java.util.jar        JarFile]
   [java.util            Properties]
   [java.net             URL URLClassLoader]
   [java.util.concurrent ConcurrentLinkedQueue LinkedBlockingQueue TimeUnit]
   [java.nio.file        Files])
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

(defn pom-properties
  [jarpath]
  (let [jarfile (JarFile. (io/file jarpath))]
    (doto (Properties.)
      (.load (->> jarfile .entries enumeration-seq
               (filter #(.endsWith (.getName %) "/pom.properties"))
               first
               (.getInputStream jarfile))))))

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
  (doto (Properties.)
    (.load (io/input-stream (dependency-loaded? coord)))))

(defn dependency-pom-properties-map
  [coord]
  (pom-prop-map (dependency-pom-properties coord)))

(defn pom-properties-map
  [prop-or-jarpath]
  (pom-prop-map
    (if (instance? Properties prop-or-jarpath)
      prop-or-jarpath
      (doto (Properties.) (.load (io/input-stream prop-or-jarpath))))))

(defn pom-xml
  [jarpath]
  (let [jarfile (JarFile. (io/file jarpath))]
    (some->> jarfile .entries enumeration-seq
      (filter #(.endsWith (.getName %) "/pom.xml"))
      first (.getInputStream jarfile) slurp)))

(defn copy-resource
  [resource-path out-path]
  (with-open [in  (io/input-stream (io/resource resource-path))
              out (io/output-stream (doto (io/file out-path) io/make-parents))]
    (io/copy in out)))

(defn copy-url
  [url-str out-path]
  (with-open [in  (io/input-stream url-str)
              out (io/output-stream (doto (io/file out-path) io/make-parents))]
    (io/copy in out)))

(def  pod-id         (atom nil))
(def  worker-pod     (atom nil))
(def  shutdown-hooks (atom nil))

(defn set-worker-pod!
  [pod]
  (reset! worker-pod pod))

(defn add-shutdown-hook!
  [f]
  (if (not= 1 @pod-id)
    (.offer @shutdown-hooks f)
    (->> f Thread. (.addShutdownHook (Runtime/getRuntime)))))

(defn- eval-fn-call
  [[f & args]]
  (when-let [ns (namespace f)] (require (symbol ns)))
  (if-let [f (resolve f)]
    (apply f args)
    (throw (Exception. (format "can't resolve symbol (%s)" f)))))

(defn call-in
  ([expr]
     (let [{:keys [meta? expr]} (read-string expr)]
       (binding [*print-meta* meta?]
         (pr-str (eval-fn-call expr)))))
  ([pod expr]
     (let [ret (.invoke pod "boot.pod/call-in"
                 (pr-str {:meta? *print-meta* :expr expr}))]
       (util/guard (read-string ret)))))

(defn call-worker
  [expr]
  (if @worker-pod (call-in @worker-pod expr) (eval-fn-call expr)))

(defn eval-in*
  ([expr]
     (let [{:keys [meta? expr]} (read-string expr)]
       (binding [*print-meta* meta?]
         (pr-str (eval expr)))))
  ([pod expr]
     (let [ret (.invoke pod "boot.pod/eval-in*"
                 (pr-str {:meta? *print-meta* :expr expr}))]
       (util/guard (read-string ret)))))

(defmacro eval-in
  [pod & body]
  `(eval-in* ~pod (bt/template (do ~@body))))

(defmacro eval-worker
  [& body]
  `(eval-in @worker-pod ~@body))

(defn require-in
  [pod ns]
  (doto pod (.require (into-array String [(str ns)]))))

(defn canonical-coord
  [[id & more :as coord]]
  (let [[ns nm] ((juxt namespace name) id)]
    (assoc-in (vec coord) [0] (if (not= ns nm) id (symbol nm)))))

(defn resolve-dependencies
  [env]
  (call-worker `(boot.aether/resolve-dependencies ~env)))

(defn resolve-dependency-jars
  [env]
  (->> env resolve-dependencies (map (comp io/file :jar))))

(defn resolve-dependency-jar
  [env coord]
  (let [coord (canonical-coord coord)]
    (->> [coord] (assoc env :dependencies) resolve-dependencies
      (filter #(= (first coord) (first (:dep %)))) first :jar)))

(defn outdated
  [env & {:keys [snapshots]}]
  (let [v+ (if snapshots "(0,)" "RELEASE")]
    (->> (for [[p v & _ :as coord] (->> env :dependencies (map canonical-coord))]
           (util/guard
             (let [env' (-> env (assoc :dependencies [(assoc coord 1 v+)]))
                   [p' v' & _ :as coord'] (->> (map :dep (resolve-dependencies env'))
                                               (filter #(= p (first %))) first)]
               (and (= p p') (not= v v') coord))))
         (filter identity))))

(defn add-dependencies
  [env]
  (doseq [jar (resolve-dependency-jars env)] (add-classpath jar)))

(defn add-dependencies-in
  [pod env]
  (call-in pod
    `(boot.pod/add-dependencies ~env)))

(defn add-dependencies-worker
  [env]
  (add-dependencies-in @worker-pod env))

(def jar-entries
  "Given a path to a jar file, returns a list of [resource-path, resource-url]
  string pairs corresponding to all entries contained the jar contains."
  (memoize
    (fn [path-or-jarfile]
      (when path-or-jarfile
        (let [f    (io/file path-or-jarfile)
              path (.getPath f)]
          (when (.endsWith path ".jar")
            (when (and (.exists f) (.isFile f))
              (->> f JarFile. .entries enumeration-seq
                (keep #(when-not (.isDirectory %)
                         (let [name (.getName %)]
                           [name (->> (io/file (io/file (str path "!")) name)
                                   .toURI .toURL .toString (str "jar:"))])))))))))))

(defn jars-in-dep-order
  [env]
  (map io/file (call-worker `(boot.aether/jars-in-dep-order ~env))))

(defn copy-dependency-jar-entries
  [env outdir coord & regexes]
  (let [keep? (if-not (seq regexes)
                (constantly true)
                (apply some-fn (map #(partial re-find %) regexes)))
        ents  (->> (resolve-dependency-jar env coord)
                jar-entries
                (filter (comp keep? first))
                (map (fn [[k v]] [v (.getPath (io/file outdir k))])))]
    (doseq [[url-str out-path] ents] (copy-url url-str out-path))))

(defn- set-this-worker-in-pod!
  [pod]
  (doto pod
    (require-in "boot.pod")
    (.invoke "boot.pod/set-worker-pod!" @worker-pod)))

(defn lifecycle-pool
  [size create destroy & {:keys [priority]}]
  (let [run? (atom true)
        pri  (or priority Thread/NORM_PRIORITY)
        q    (LinkedBlockingQueue. (int size))
        poll #(.poll % 1 TimeUnit/SECONDS)
        putp #(util/with-let [p (promise)] (.put % p))
        fill #(util/while-let [p (and @run? (putp q))]
                (deliver p (when @run? (create))))
        take #(deref (.peek q))
        swap #(destroy @(.take q))
        stop #(do (reset! run? false)
                  (util/while-let [p (poll q)]
                    (destroy @p)))]
    (doto (Thread. fill) (.setDaemon true) (.setPriority pri) .start)
    (fn
      ([] (take))
      ([op] (case op :refresh (swap) :shutdown (stop))))))

(defn make-pod
  ([] (set-this-worker-in-pod! (boot.App/newPod)))
  ([{:keys [directories] :as env}]
     (let [dirs (map io/file directories)
           jars (resolve-dependency-jars env)]
       (set-this-worker-in-pod!
         (->> (concat dirs jars) (into-array java.io.File) (boot.App/newPod))))))

(defn destroy-pod
  [pod]
  (when pod
    (.invoke pod "clojure.core/shutdown-agents")
    (.. pod getClassLoader close)))

(defn pod-pool
  [size env]
  (lifecycle-pool size #(make-pod env) destroy-pod))
