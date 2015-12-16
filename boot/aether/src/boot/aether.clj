(ns boot.aether
  (:require
   [clojure.java.io             :as io]
   [clojure.string              :as string]
   [clojure.pprint              :as pprint]
   [cemerick.pomegranate.aether :as aether]
   [boot.util                   :as util]
   [boot.pod                    :as pod]
   [boot.gpg                    :as gpg]
   [boot.from.io.aviso.ansi     :as ansi]
   [boot.kahnsort               :as ksort])
  (:import
   [boot App]
   [java.io File]
   [java.util.jar JarFile]
   [java.util.regex Pattern]
   [org.sonatype.aether.resolution DependencyResolutionException]))

(def offline?             (atom false))
(def update?              (atom :daily))
(def local-repo           (atom nil))
(def default-repositories (atom [["clojars"       {:url "https://clojars.org/repo/"}]
                                 ["maven-central" {:url "https://repo1.maven.org/maven2/"}]]))

(defn set-offline!    [x] (reset! offline? x))
(defn set-update!     [x] (reset! update? x))
(defn update-always!  []  (set-update! :always))
(defn set-local-repo! [x] (reset! local-repo x))

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error :as info}]
  (util/dbug "Aether: %s\n" (with-out-str (pprint/pprint info)))
  (when (and (.endsWith name ".jar") (= type :started))
    (util/info "Retrieving %s from %s\n" (.getName (io/file name)) repo)))

(defn ^{:boot/from :technomancy/leiningen} build-url
  "Creates java.net.URL from string"
  [url]
  (try (java.net.URL. url)
       (catch java.net.MalformedURLException _
         (java.net.URL. (str "http://" url)))))

(defn ^{:boot/from :technomancy/leiningen} get-non-proxy-hosts
  []
  (let [system-no-proxy (System/getenv "no_proxy")]
    (if (not-empty system-no-proxy)
      (->> (string/split system-no-proxy #",")
           (map #(str "*" %))
           (string/join "|")))))

(defn ^{:boot/from :technomancy/leiningen} get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
     (if-let [proxy (System/getenv key)]
       (let [url (build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host            (.getHost url)
          :port            (.getPort url)
          :username        username
          :password        password
          :non-proxy-hosts (get-non-proxy-hosts)}))))

(defn resolve-dependencies*
  [env]
  (try
    (aether/resolve-dependencies
      :coordinates       (:dependencies env)
      :repositories      (->> (or (seq (:repositories env)) @default-repositories)
                           (map (juxt first (fn [[x y]] (if (map? y) y {:url y}))))
                           (map (juxt first (fn [[x y]] (update-in y [:update] #(or % @update?))))))
      :local-repo        (or (:local-repo env) @local-repo nil)
      :offline?          (or @offline? (:offline? env))
      :mirrors           (:mirrors env)
      :proxy             (or (:proxy env) (get-proxy-settings))
      :transfer-listener transfer-listener
      :repository-session-fn (if (= @update? :always)
                               #(doto (aether/repository-session %)
                                  (.setUpdatePolicy (aether/update-policies :always)))
                               aether/repository-session))
    (catch Exception e
      (let [root-cause (last (take-while identity (iterate (memfn getCause) e)))]
        (if-not (and (not @offline?) (instance? java.net.UnknownHostException root-cause))
          (throw e)
          (do (reset! offline? true)
              (resolve-dependencies* env)))))))

(def resolve-dependencies-memoized* (memoize resolve-dependencies*))

(defn- dep->path [dep] (->> dep meta :file .getPath))

(defn resolve-dependencies
  "Given an env map, returns a list of maps of the form
     {:dep [foo/bar \"1.2.3\"], :jar \"file:...\"}
   corresponding to the resolved dependencies (including transitive deps)."
  [env]
  (->> [:dependencies :repositories :local-repo :offline? :mirrors :proxy]
    (select-keys env)
    resolve-dependencies-memoized*
    ksort/topo-sort
    (map (fn [x] {:dep x :jar (dep->path x)}))))

(defn resolve-dependency-jars
  "Given an env map, resolves dependencies and returns a list of dependency jar 
  file paths.

  The 3-arity is used by boot.App to resolve dependencies for the core pods.
  It returns a Java array of java.io.File objects corresponding to the resolved
  dependencies."
  ([env] (->> env resolve-dependencies (map :jar)))
  ([sym-str version cljversion] (resolve-dependency-jars sym-str version nil cljversion))
  ([sym-str version cljname cljversion]
     (let [cljname (or cljname "org.clojure/clojure")]
       (->> {:dependencies [[(symbol cljname) cljversion] [(symbol sym-str) version]]}
            resolve-dependencies (map (comp io/file :jar)) (into-array java.io.File)))))

(defn resolve-nontransitive-dependencies
  "Given an env map and a single dependency coordinates vector, resolves the
  dependency and returns its direct, non-transitive dependencies. Dependencies
  are returned as a list of maps of the same form as resolve-dependencies above."
  [env dep]
  (->> (assoc env :dependencies [dep])
       resolve-dependencies-memoized*
       (#(get % dep))
       (map (fn [x] {:dep x :jar (dep->path x)}))))

(def jars-dep-graph
  "Given an env map, returns a dependency graph for all jar files on on the
  classpath."
  (memoize
    (fn [env]
      (let [resol  resolve-dependencies-memoized*
            resol' #(->> % (assoc env :dependencies) resol)
            deps   (->> env resol keys)
            jars   (-> (fn [xs [k & _ :as x]]
                         (assoc xs k (dep->path x)))
                       (reduce {} deps))]
        (->> deps
          (pmap #(vector % (get (resol' (vector %)) %)))
          (map (fn [[[k & _] v]] [(jars k) (set (map (comp jars first) v))]))
          (into {}))))))

(defn jars-in-dep-order
  "Given an env map, returns a list of all jar files on on the classpath in
  dependency order. Dependency order means, eg. if jar B depends on jar A then
  jar A will appear before jar B in the returned list."
  [env]
  (->> env jars-dep-graph ksort/topo-sort reverse))

(defn dep-tree
  "Returns the printed dependency graph as a string."
  [env]
  (->> env
    resolve-dependencies-memoized*
    (aether/dependency-hierarchy (:dependencies env))
    util/print-tree
    with-out-str))

(defn- pom-xml-parse-string
  [pom-str]
  ;; boot.pom is not available from the aether uberjar that is used the
  ;; first time boot is run (a minimal environment just containing enough
  ;; clojure to get pomegranate working to resolve boot's own maven deps).
  ;; We don't call any functions that use this in that case, but we need
  ;; to dynamically :require the boot.pom namespace here.
  (require 'boot.pom)
  (@(resolve 'boot.pom/pom-xml-parse-string) pom-str))

(defn- pom-xml-tmp
  [pom-str]
  (doto (File/createTempFile "pom" ".xml") (.deleteOnExit) (spit pom-str)))

(defn install
  ([env jarpath]
   (install env jarpath nil))
  ([env jarpath pompath]
   (let [pom-str                   (pod/pom-xml jarpath pompath)
         {:keys [project version]} (pom-xml-parse-string pom-str)
         pomfile                   (pom-xml-tmp pom-str)]
     (aether/install
       :coordinates [project version]
       :jar-file    (io/file jarpath)
       :pom-file    (io/file pomfile)
       :local-repo  (or (:local-repo env) @local-repo nil)))))

(defn deploy
  ([env repo jarpath]
   (deploy env repo jarpath nil))
  ([env repo jarpath pom-or-artifacts]
   (if (map? pom-or-artifacts)
     (deploy env repo jarpath nil pom-or-artifacts)
     (deploy env repo jarpath pom-or-artifacts nil)))
  ([env [repo-id repo-settings] jarpath pompath artifact-map]
   (let [pom-str                   (pod/pom-xml jarpath pompath)
         {:keys [project version]} (pom-xml-parse-string pom-str)
         pomfile                   (pom-xml-tmp pom-str)]
     (aether/deploy
       :coordinates  [project version]
       :jar-file     (io/file jarpath)
       :pom-file     (io/file pomfile)
       :artifact-map artifact-map
       :repository   {repo-id repo-settings}
       :local-repo   (or (:local-repo env) @local-repo nil)))))

(def ^:private wagon-files (atom #{}))

(defn load-wagon-mappings
  [& [mapping]]
  (locking wagon-files
    (->> (pod/resources "leiningen/wagons.clj")
      (remove (partial contains? @wagon-files))
      (map #(do (swap! wagon-files conj %)
                (->> % io/input-stream slurp read-string)))
      (reduce into {})
      (mapv (fn [[k v]] (aether/register-wagon-factory! k (eval v))))))
  (doseq [[scheme factory] mapping]
    (aether/register-wagon-factory! scheme (eval factory))))

(defn add-wagon
  [env coord & [mapping]]
  (pod/add-dependencies (assoc env :dependencies [coord]))
  (load-wagon-mappings mapping))
