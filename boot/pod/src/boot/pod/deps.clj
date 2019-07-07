(ns boot.pod.deps
  (:require [boot.core :as boot]
            [boot.pod :as pod]))

(defn- make-pod
  "Make and return a Boot pod with tools.deps.alpha and a recent Clojure
  release (to ensure that tools.deps.alpha will run). The pod will also
  include Boot and boot-tools-deps (since they're both running), as well as
  any other 'runtime' dependencies (which is why it's best to avoid putting
  additional dependencies in build.boot when using this tool!)."
  []
  (let [pod-env
        (-> (boot/get-env)
            ;; Pod class path needs to be bootstrapped independently of
            ;; the core Pod (build.boot context) so that, for example,
            ;; an older version of Clojure than 1.9 can be used in the
            ;; core Pod.
            (dissoc :boot-class-path :fake-class-path)
            ;; Clojure version in the core Pod (build.boot context)
            ;; is not guaranteed to be recent enough as Boot supports
            ;; 1.6.0 onwards. If it isn't recent enough, we replace it.
            ;; We also force tools.deps.alpha to a fixed version.
            (update :dependencies ensure-recent-clojure-tools-deps))]
    (pod/make-pod pod-env)))

(defn build-environment-map
  "Run tools.deps to produce:
  * :resource-paths  -- source code directories from :paths in deps.edn files
  * :source-paths -- additional directories from :extra-paths and classpath
  * :repositories -- any entries from :mvn/repos
  * :dependencies -- vector of Maven coordinates
  * :classpath -- JAR files to add to the classpath
  * :main-opts -- any main-opts pulled from tools.deps.alpha"
  [{:keys [config-data ; no config-paths
           classpath-aliases main-aliases resolve-aliases
           verbose ; no repeatable
           system-deps deps-files total]
    :as options}]
  (let [deps         (reader/read-deps
                       (into [] (comp (map io/file)
                                      (filter #(.exists %)))
                             deps-files))
        deps         (if total
                       (if config-data
                         (reader/merge-deps [deps config-data])
                         deps)
                       (reader/merge-deps
                         (cond-> [system-deps deps]
                           config-data (conj config-data))))
        paths        (set (or (seq (:paths deps)) []))
        resolve-args (cond->
                       (deps/combine-aliases deps resolve-aliases)
                       ;; handle both legacy boolean and new counter
                       (and verbose
                            (or (boolean? verbose)
                                (< 1 verbose)))
                       (assoc :verbose true))
        libs         (deps/resolve-deps deps resolve-args)
        cp-args      (deps/combine-aliases deps classpath-aliases)
        cp           (deps/make-classpath libs (:paths deps) cp-args)
        main-opts    (:main-opts (deps/combine-aliases deps main-aliases))
        cp-separator (re-pattern java.io.File/pathSeparator)
        [jars dirs]  (reduce (fn [[jars dirs] item]
                               (let [f (java.io.File. item)]
                                 (if (and (.exists f)
                                          (not (paths item)))
                                   (cond (.isFile f)
                                         [(conj jars item) dirs]
                                         (.isDirectory f)
                                         [jars (conj dirs item)]
                                         :else
                                         [jars dirs])
                                   [jars dirs])))
                             [[] []]
                             (str/split cp cp-separator))]
    {:resource-paths paths
     :source-paths   (set dirs)
     :repositories   (:mvn/repos deps)
     :dependencies   libs
     :classpath      jars
     :main-opts      main-opts}))
